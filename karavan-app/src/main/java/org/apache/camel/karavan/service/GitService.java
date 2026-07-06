/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.mutiny.tuples.Tuple3;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.model.*;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-project Git. There is no global/service-account repository: every Git
 * operation targets the project's own remote ({@link ProjectFolder#getGitRepository()}
 * / {@link ProjectFolder#getGitBranch()}) and authenticates with the acting
 * user's credentials ({@link UserGitConfig}, configured in System -> Git). A
 * project with no remote configured has no Git operations available.
 * <p>
 * Files of a project live under a {@code <projectId>/} folder inside its repo
 * (the same layout the build container expects: {@code git clone} then
 * {@code cd <repo>/<projectId>}).
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class GitService {

    // Optional SSH key material for the build container (mounted into builds)
    // comes from karavan.private-key-path / karavan.known-hosts-path. Not used
    // to authenticate the control-plane's own Git operations, which are
    // HTTPS + per-user token only.
    private final KaravanConfig config;

    private final Vertx vertx;

    public Tuple2<String, String> getSShFiles() {
        return Tuple2.of(config.privateKeyPath().orElse(null), config.knownHostsPath().orElse(null));
    }

    public boolean hasRemote(ProjectFolder projectFolder) {
        return projectFolder != null
                && projectFolder.getGitRepository() != null
                && !projectFolder.getGitRepository().isBlank();
    }

    /**
     * Build the Git config for an operation on a project from the project's own
     * remote and the acting user's credentials. Requires the project to have a
     * remote configured.
     */
    public GitConfig resolveGitConfig(ProjectFolder projectFolder, UserGitConfig user) {
        if (!hasRemote(projectFolder)) {
            throw new IllegalStateException("Project has no Git repository configured: "
                    + (projectFolder != null ? projectFolder.getProjectId() : "null"));
        }
        String branch = (projectFolder.getGitBranch() != null && !projectFolder.getGitBranch().isBlank())
                ? projectFolder.getGitBranch() : "main";
        String u = user != null ? user.getGitUsername() : null;
        String p = user != null ? user.getGitToken() : null;
        return new GitConfig(projectFolder.getGitRepository().trim(), u, p, branch, null);
    }

    public Tuple3<RevCommit, List<RemoteRefUpdate.Status>, List<String>> commitAndPushProject(ProjectFolder projectFolder, List<ProjectFile> files, String message, String authorName, String authorEmail, List<String> fileNames, UserGitConfig user) throws GitAPIException, IOException, URISyntaxException {
        log.info("Commit and push project " + projectFolder.getProjectId());
        GitConfig gitConfig = resolveGitConfig(projectFolder, user);
        String uuid = UUID.randomUUID().toString();
        String folder = vertx.fileSystem().createTempDirectoryBlocking(uuid);
        log.info("Temp folder created " + folder);
        Git git = getGit(true, folder, gitConfig);
        writeProjectToFolder(folder, projectFolder, files);
        addDeletedFilesToIndex(git, folder, projectFolder, files);
        return commitAddedAndPush(git, gitConfig.getBranch(), message, authorName, authorEmail, fileNames, projectFolder.getProjectId(), gitConfig);
    }

    public List<PathCommitDetails> readProjectFromRepository(ProjectFolder projectFolder, UserGitConfig user) throws GitAPIException, IOException, URISyntaxException {
        String projectId = projectFolder.getProjectId();
        GitConfig gitConfig = resolveGitConfig(projectFolder, user);
        Git git = getGit(true, vertx.fileSystem().createTempDirectoryBlocking(UUID.randomUUID().toString()), gitConfig);
        return readProjectsFromRepository(git, projectId).stream().filter(d -> Objects.equals(d.projectId(), projectId)).toList();
    }

    /**
     * Clone a project's own repo into {@code folder} for read-only inspection (e.g. commit history).
     */
    public Git getProjectGit(ProjectFolder projectFolder, UserGitConfig user, String folder) throws GitAPIException, IOException, URISyntaxException {
        return getGit(true, folder, resolveGitConfig(projectFolder, user));
    }

    public List<PathCommitDetails> getLastCommitForEachFile(Git git) throws IOException, GitAPIException {
        List<PathCommitDetails> pathCommitDetails = new ArrayList<>();
        Repository repository = git.getRepository();

        // Resolve the HEAD commit (the current state of the branch)
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null) {
            throw new IllegalStateException("Repository has no HEAD. Is it an empty repository?");
        }

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit headCommit = revWalk.parseCommit(head);
            RevTree tree = headCommit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(false); // keep folder nodes so we can record per-project folders

                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    boolean isFolder = treeWalk.isSubtree();
                    Iterable<RevCommit> commits = git.log().addPath(path).setMaxCount(1).call();

                    if (isFolder && !path.startsWith(".")) {
                        for (RevCommit commit : commits) {
                            String commitId = commit.getName();
                            Long commitTime = Integer.valueOf(commit.getCommitTime()).longValue() * 1000;
                            pathCommitDetails.add(new PathCommitDetails(path, null, commitId, commitTime, null, true));
                        }
                        treeWalk.enterSubtree();
                    } else {
                        ObjectId blobId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(blobId);
                        String content = new String(loader.getBytes(), StandardCharsets.UTF_8);

                        for (RevCommit commit : commits) {
                            String commitId = commit.getName();
                            Long commitTime = Integer.valueOf(commit.getCommitTime()).longValue() * 1000;
                            String[] parts = path.split(Pattern.quote(File.separator));
                            if (parts.length == 2) {
                                var projectId = parts[0];
                                var fileName = parts[1];
                                pathCommitDetails.add(new PathCommitDetails(projectId, fileName, commitId, commitTime, content, false));
                            }
                        }
                    }
                }
            }
        }

        return pathCommitDetails;
    }

    private List<PathCommitDetails> readProjectsFromRepository(Git git, String... filter) {
        log.info("Read projects...");
        List<PathCommitDetails> result = new ArrayList<>();
        try {
            return getLastCommitForEachFile(git);
        } catch (RefNotFoundException e) {
            log.error("New repository");
            return result;
        } catch (IllegalStateException e) {
            // Empty repo (no HEAD yet) — nothing to import.
            log.info(e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("Error", e);
            return result;
        }
    }

    /**
     * Clone the project's remote into {@code folder}. If the remote exists but the
     * target branch is missing (a brand-new/empty repo), initialize it locally so a
     * first push can create the branch. Authentication/connectivity failures
     * (TransportException) propagate so the caller can surface a proper error.
     */
    public Git getGit(boolean checkout, String folder, GitConfig gitConfig) throws GitAPIException, IOException, URISyntaxException {
        log.info("Git checkout " + gitConfig.getUri());
        Git git;
        try {
            // A branch newly created in Karavan (e.g. to test a different runtime) does
            // not exist on the remote yet. Cloning with --branch <new> fails hard with
            // "Remote branch '<new>' not found in upstream origin" (a TransportException,
            // NOT the RefNotFoundException caught below), which broke commit/push/build.
            // So when the target branch is missing remotely, clone the remote's default
            // branch and create the new branch locally; the first commit+push then
            // creates it on the remote.
            boolean branchExists = remoteBranchExists(gitConfig);
            git = clone(folder, gitConfig, branchExists);
            if (!branchExists) {
                createLocalBranch(git, gitConfig.getBranch());
            } else if (checkout) {
                checkout(git, false, null, null, gitConfig.getBranch());
            }
        } catch (RefNotFoundException | InvalidRemoteException e) {
            // Reachable remote with no refs at all yet (brand-new/empty repo) -> init locally.
            log.warn("New/empty repository, initializing locally: " + e.getMessage());
            git = init(folder, gitConfig.getUri(), gitConfig.getBranch());
        }
        return git;
    }

    /**
     * Whether {@code gitConfig.getBranch()} already exists on the remote (git ls-remote).
     */
    private boolean remoteBranchExists(GitConfig gitConfig) {
        try {
            return listRemoteBranches(gitConfig.getUri(), gitConfig.getUsername(), gitConfig.getPassword())
                    .stream().anyMatch(b -> Objects.equals(b, gitConfig.getBranch()));
        } catch (Exception e) {
            // Couldn't list (brand-new/empty repo or a transient error): assume the branch
            // is absent so we attempt to create it. A real auth/connectivity failure
            // resurfaces on the clone with a clearer message.
            log.warn("Could not list remote branches for " + gitConfig.getUri() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Create the (remotely-missing) branch locally off the cloned default branch and
     * switch to it, so the project's files are committed onto the new branch and the
     * subsequent push creates it on the remote.
     */
    private void createLocalBranch(Git git, String branch) throws GitAPIException {
        log.info("Remote branch '" + branch + "' is missing; creating it locally");
        git.checkout().setCreateBranch(true).setName(branch).call();
    }

    private void writeProjectToFolder(String folder, ProjectFolder projectFolder, List<ProjectFile> files) throws IOException {
        Files.createDirectories(Paths.get(folder, projectFolder.getProjectId()));
        log.info("Write files for project " + projectFolder.getProjectId());
        files.forEach(file -> {
            try {
                log.info("Add file " + file.getName());
                Files.writeString(Paths.get(folder, projectFolder.getProjectId(), file.getName()), file.getCode());
            } catch (IOException e) {
                log.error("Error during file write", e);
            }
        });
    }

    private void addDeletedFilesToIndex(Git git, String folder, ProjectFolder projectFolder, List<ProjectFile> files) throws IOException {
        Path path = Paths.get(folder, projectFolder.getProjectId());
        log.info("Add deleted files to git index for project " + projectFolder.getProjectId());
        vertx.fileSystem().readDirBlocking(path.toString()).forEach(f -> {
            String[] filenames = f.split(Pattern.quote(File.separator));
            String filename = filenames[filenames.length - 1];
            log.info("Checking file " + filename);
            if (files.stream().filter(pf -> Objects.equals(pf.getName(), filename)).count() == 0) {
                try {
                    log.info("Add deleted file " + filename);
                    git.rm().addFilepattern(projectFolder.getProjectId() + File.separator + filename).call();
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public Tuple3<RevCommit, List<RemoteRefUpdate.Status>, List<String>> commitAddedAndPush(Git git, String branch, String message, String authorName, String authorEmail, List<String> fileNames, String projectId, GitConfig gitConfig) throws GitAPIException {
        log.info("Commit and push changes to the branch " + branch);
        AddCommand add = git.add();
        for (String fileName : fileNames) {
            add = add.addFilepattern(projectId + File.separator + fileName);
        }
        log.info("Git add: " + add.call());
        RevCommit commit = git.commit().setMessage(message).setAuthor(new PersonIdent(authorName, authorEmail)).call();
        List<String> messages = new ArrayList<>();
        List<RemoteRefUpdate.Status> statuses = new ArrayList<>();
        log.info("Git commit: " + commit);
        PushCommand pushCommand = git.push();
        pushCommand.add(branch).setRemote("origin");
        setCredentials(pushCommand, gitConfig);
        Iterable<PushResult> results = pushCommand.call();
        for (PushResult pr : results) {
            if (pr != null) {
                log.info("Git push result: " + pr.getMessages());
                for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                    log.info("Git push: " + rru.getStatus() + ", " + rru.getMessage());
                    if (RemoteRefUpdate.Status.OK != rru.getStatus()) {
                        statuses.add(rru.getStatus());
                        messages.add(rru.getMessage());
                    }
                }
                messages.add(pr.getMessages());
            }
        }
        return Tuple3.of(commit, statuses, messages);
    }

    public Git init(String dir, String uri, String branch) throws GitAPIException, IOException, URISyntaxException {
        Git git = Git.init().setInitialBranch(branch).setDirectory(Path.of(dir).toFile()).call();
        addRemote(git, uri);
        return git;
    }

    // NOTE: project deletion is local-only by design (cache + database).
    // Karavan never deletes or rewrites a project's remote repository — the
    // remote keeps its history so the project can be re-imported later.

    private Git clone(String dir, GitConfig gitConfig, boolean useBranch) throws GitAPIException, URISyntaxException {
        CloneCommand command = Git.cloneRepository();
        command.setCloneAllBranches(false);
        command.setDirectory(Paths.get(dir).toFile());
        command.setURI(gitConfig.getUri());
        // Only pin the branch when it exists on the remote. For a not-yet-pushed branch
        // we clone the remote's default branch (the caller creates the branch locally).
        if (useBranch) {
            command.setBranch(gitConfig.getBranch());
        }
        setCredentials(command, gitConfig);
        Git git = command.call();
        addRemote(git, gitConfig.getUri());
        return git;
    }

    private void addRemote(Git git, String uri) throws URISyntaxException, GitAPIException {
        RemoteAddCommand remoteAddCommand = git.remoteAdd();
        remoteAddCommand.setName("origin");
        remoteAddCommand.setUri(new URIish(uri));
        remoteAddCommand.call();
    }

    private void checkout(Git git, boolean create, String path, String startPoint, String branch) throws GitAPIException {
        CheckoutCommand checkoutCommand = git.checkout();
        checkoutCommand.setName(branch);
        checkoutCommand.setCreateBranch(create);
        if (startPoint != null) {
            checkoutCommand.setStartPoint(startPoint);
        }
        if (path != null) {
            checkoutCommand.addPath(path);
        }
        checkoutCommand.call();
    }

    /**
     * List the branches of a remote repository without cloning it (git ls-remote
     * --heads). Used by the "Fetch branches" action when configuring a project's
     * Git remote. Credentials are the calling user's stored Git username/token; a
     * null/blank pair attempts an anonymous (public-repo) listing.
     */
    public List<String> listRemoteBranches(String repoUrl, String gitUsername, String gitToken) throws GitAPIException {
        LsRemoteCommand command = Git.lsRemoteRepository()
                .setRemote(repoUrl)
                .setHeads(true)
                .setTags(false);
        // A token alone is enough (GitHub/GitLab PATs): use the username if set,
        // otherwise the token doubles as the username.
        if (gitToken != null && !gitToken.isBlank()) {
            String user = (gitUsername != null && !gitUsername.isBlank()) ? gitUsername : gitToken;
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, gitToken));
        }
        List<String> branches = new ArrayList<>();
        for (Ref ref : command.call()) {
            String name = ref.getName();
            if (name.startsWith(Constants.R_HEADS)) {
                branches.add(name.substring(Constants.R_HEADS.length()));
            }
        }
        branches.sort(String.CASE_INSENSITIVE_ORDER);
        return branches;
    }

    private <T extends TransportCommand> T setCredentials(T command, GitConfig gitConfig) {
        // A token (password) alone is enough (GitHub/GitLab PATs): use the username
        // if provided, otherwise the token doubles as the username.
        String token = gitConfig.getPassword();
        if (token != null && !token.isBlank()) {
            String user = (gitConfig.getUsername() != null && !gitConfig.getUsername().isBlank())
                    ? gitConfig.getUsername() : token;
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token));
        }
        return command;
    }
}
