package org.apache.camel.karavan.service;

import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.ProjectFileCommitDiff;
import org.apache.camel.karavan.model.ProjectFolder;
import org.apache.camel.karavan.model.ProjectFolderCommit;
import org.apache.camel.karavan.model.UserGitConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

@Slf4j
@Singleton
public class GitHistoryService {

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    Vertx vertx;

    @Inject
    GitService gitService;

    @Inject
    KaravanCache karavanCache;

    public void importProjectCommits(ProjectFolder projectFolder, UserGitConfig user) {
        String projectId = projectFolder.getProjectId();
        log.info("Import commits for " + projectId);
        managedExecutor.runAsync(() -> {
            var commits = getProjectCommits(projectFolder, user, 10);
            karavanCache.saveProjectLastCommits(projectId, commits);
        });
    }

    public List<ProjectFolderCommit> getProjectCommits(ProjectFolder projectFolder, UserGitConfig user, int maxCount) {
        String projectId = projectFolder.getProjectId();
        List<ProjectFolderCommit> result = new ArrayList<>();
        try {
            Git pollGit = gitService.getProjectGit(projectFolder, user, vertx.fileSystem().createTempDirectoryBlocking("commits"));
            if (pollGit == null) return result;

            Repository repo = pollGit.getRepository();

            Iterable<RevCommit> commits = pollGit.log()
                    .setMaxCount(maxCount)
                    .all()
                    .addPath(projectId)
                    .call();
            StreamSupport.stream(commits.spliterator(), false)
                    .sorted(Comparator.comparingInt(RevCommit::getCommitTime).reversed())
                    .forEach(commit -> {
                        try {
                            List<ProjectFileCommitDiff> diffs = buildDiffsWithBeforeAfter(repo, commit, projectId);

                            ProjectFolderCommit projectCommit = new ProjectFolderCommit(
                                    commit.getId().getName(),
                                    projectId,
                                    commit.getAuthorIdent().getName(),
                                    commit.getAuthorIdent().getEmailAddress(),
                                    commit.getCommitTime() * 1000L,
                                    commit.getShortMessage(),
                                    diffs
                            );

                            result.add(projectCommit);
                        } catch (Exception e) {
                            log.error("Error building diffs for commit " + commit.getId().getName(), e);
                        }
                    });

        } catch (Exception e) {
            log.error("Error", e);
        }

        return result;
    }

    private List<ProjectFileCommitDiff> buildDiffsWithBeforeAfter(Repository repo, RevCommit commit, String projectId) throws Exception {
        List<ProjectFileCommitDiff> out = new ArrayList<>();

        try (RevWalk revWalk = new RevWalk(repo)) {
            revWalk.parseHeaders(commit);

            RevCommit parent = commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0).getId()) : null;

            AbstractTreeIterator oldTreeIter = (parent == null)
                    ? new EmptyTreeIterator()
                    : treeIterator(repo, parent);

            AbstractTreeIterator newTreeIter = treeIterator(repo, commit);

            // 1) Collect DiffEntry list
            List<DiffEntry> entries;
            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repo);
                df.setDiffComparator(RawTextComparator.DEFAULT);
                df.setDetectRenames(true);

                entries = df.scan(oldTreeIter, newTreeIter);
            }

            // 2) For each entry, produce: diff text + before/after
            for (DiffEntry entry : entries) {
                // Optional: keep only diffs under the project path
                if (!isUnderProjectPath(entry, projectId)) continue;

                String patchText = formatUnifiedDiff(repo, entry);

                String before = readBlobAsText(repo, entry.getOldId().toObjectId());
                String after = readBlobAsText(repo, entry.getNewId().toObjectId());

                // For added/deleted files, one side will be /dev/null and ObjectId may be zero
                if (isZeroId(entry.getOldId().toObjectId())) before = null;
                if (isZeroId(entry.getNewId().toObjectId())) after = null;

                ProjectFileCommitDiff d = new ProjectFileCommitDiff();
                d.setChangeType(entry.getChangeType().name());
                d.setOldPath(entry.getOldPath());
                d.setNewPath(entry.getNewPath());
                d.setDiff(patchText);
                d.setBefore(before);
                d.setAfter(after);

                out.add(d);
            }
        }

        return out;
    }

    private AbstractTreeIterator treeIterator(Repository repo, RevCommit commit) throws Exception {
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit parsed = revWalk.parseCommit(commit.getId());
            ObjectId treeId = parsed.getTree().getId();

            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (var reader = repo.newObjectReader()) {
                parser.reset(reader, treeId);
            }
            return parser;
        }
    }

    private String formatUnifiedDiff(Repository repo, DiffEntry entry) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (DiffFormatter df = new DiffFormatter(baos)) {
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);
            df.format(entry);
        }

        return baos.toString(StandardCharsets.UTF_8);
    }

    private String readBlobAsText(Repository repo, ObjectId id) throws Exception {
        if (id == null || isZeroId(id)) return null;

        try {
            ObjectLoader loader = repo.open(id, Constants.OBJ_BLOB);

            // If you expect huge files, consider a size cap
            byte[] bytes = loader.getBytes();

            // If you need binary detection, add it here (e.g., scan for 0x00)
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (MissingObjectException e) {
            // Blob not available (should be rare unless repo state is odd)
            return null;
        }
    }

    private boolean isZeroId(ObjectId id) {
        return id == null || ObjectId.zeroId().equals(id);
    }

    private boolean isUnderProjectPath(DiffEntry entry, String projectId) {
        // projectId is used as a path prefix in addPath(projectId)
        // but diff scan can still contain unrelated entries in some setups; this is a safe filter.
        String p = projectId.endsWith("/") ? projectId : projectId + "/";
        String oldPath = entry.getOldPath() == null ? "" : entry.getOldPath();
        String newPath = entry.getNewPath() == null ? "" : entry.getNewPath();

        // DiffEntry uses DiffEntry.DEV_NULL for added/deleted sides
        if (DiffEntry.DEV_NULL.equals(oldPath)) oldPath = "";
        if (DiffEntry.DEV_NULL.equals(newPath)) newPath = "";

        return oldPath.startsWith(p) || newPath.startsWith(p) || oldPath.equals(projectId) || newPath.equals(projectId);
    }
}
