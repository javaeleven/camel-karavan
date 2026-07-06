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

import io.smallrye.mutiny.tuples.Tuple3;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerComposeConverter;
import org.apache.camel.karavan.docker.DockerForKaravan;
import org.apache.camel.karavan.docker.DockerStackConverter;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.model.*;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.KaravanConstants.*;
import static org.apache.camel.karavan.KaravanEvents.*;
import static org.apache.camel.karavan.service.CodeService.*;

@Slf4j
@Default
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ProjectService {

    private static final String DEFAULT_AUTHOR_NAME = "karavan";
    private static final String DEFAULT_AUTHOR_EMAIL = "karavan@test.org";

    private final KaravanConfig config;

    private final KaravanCache karavanCache;

    private final GitService gitService;

    private final CodeService codeService;

    private final ConfigService configService;

    private final KubernetesService kubernetesService;

    private final DockerForKaravan dockerForKaravan;

    private final EventBus eventBus;

    private final AuthService authService;

    public List<ProjectFolder> getAllProjects(String type) {
        Map<String, Long> lastUpdates = karavanCache.getLatestUpdatePerProject();
        return karavanCache.getFolders().stream()
                .filter(p -> type == null || Objects.equals(p.getType().name(), type))
                .sorted(Comparator.comparing(ProjectFolder::getProjectId))
                .map(p -> {
                    Long lastUpdate = lastUpdates.getOrDefault(p.getProjectId(), p.getLastUpdate());
                    // copy() preserves the per-project Git remote (gitRepository/
                    // gitBranch/gitOwner); the 4-arg constructor would drop them, so
                    // the projects list would strip git config -> the remote popup
                    // re-asks and push/pull (hasRemote) breaks.
                    ProjectFolder folder = p.copy();
                    folder.setLastUpdate(lastUpdate);
                    return folder;
                })
                .collect(Collectors.toList());
    }

    public CommitResult commitAndPushProject(String projectId, String message, List<String> fileNames) throws Exception {
        return commitAndPushProject(projectId, message, DEFAULT_AUTHOR_NAME, DEFAULT_AUTHOR_EMAIL, fileNames);
    }

    public CommitResult commitAndPushProject(String projectId, String message, String authorName, String authorEmail, List<String> fileNames) throws Exception {
        if (Objects.equals(config.environment(), DEV)) {
            log.info("Commit project: " + projectId);
            ProjectFolder p = karavanCache.getProject(projectId);
            if (p == null || !gitService.hasRemote(p)) {
                throw new Exception("Project has no Git repository configured: " + projectId);
            }
            // The remote is restricted to its owner — enforced here at the single
            // push choke point so every publisher of CMD_PUSH_PROJECT (UI push,
            // image update, ...) is covered, not just the push endpoint.
            if (p.getGitOwner() != null && !Objects.equals(p.getGitOwner(), authorName)) {
                throw new SecurityException("Git remote is restricted to " + p.getGitOwner() + "; " + authorName + " cannot push.");
            }
            UserGitConfig user = karavanCache.getUserGitConfig(authorName);
            List<ProjectFile> files = karavanCache.getProjectFiles(projectId);
            Tuple3<RevCommit, List<RemoteRefUpdate.Status>, List<String>> result = gitService.commitAndPushProject(p, files, message, authorName, authorEmail, fileNames, user);
            var commit = result.getItem1();
            var statuses = result.getItem2();
            var messages = result.getItem3();
            if (statuses.stream().noneMatch(status -> status != RemoteRefUpdate.Status.OK)) {
                String commitId = commit.getId().getName();
                Long lastUpdate = commit.getCommitTime() * 1000L;
                importProject(projectId, user);
                return new CommitResult(p, statuses, messages, commitId, lastUpdate);
            } else {
                return new CommitResult(p, statuses, messages, null, null);
            }
        } else {
            throw new RuntimeException("Unsupported environment: " + config.environment());
        }
    }

    public String runProjectInDeveloperMode(String projectId, Boolean verbose, Boolean compile, Map<String, String> labels, Map<String, String> envVars, Boolean appOnly) throws Exception {
        PodContainerStatus status = karavanCache.getDevModePodContainerStatus(projectId, config.environment());
        if (status == null) {
            status = PodContainerStatus.createDevMode(projectId, config.environment());
        }
        if (!Objects.equals(status.getState(), PodContainerStatus.State.running.name())) {
            status.setInTransit(true);
            eventBus.publish(POD_CONTAINER_UPDATED, JsonObject.mapFrom(status));

            try {
                Map<String, String> files = codeService.getProjectFilesForDevMode(projectId, true)
                        .entrySet().stream().filter(e -> !appOnly || APPLICATION_PROPERTIES_FILENAME.equals(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                String projectDevmodeImage = codeService.getProjectDevModeImage(projectId);
                // Dev mode runs in the project's own runtime (camel-main via the image CMD;
                // quarkus/spring-boot via an explicit `camel run --runtime=...`).
                String devRuntime = codeService.getProjectRuntime(projectId);
                ProjectFile devAppProps = karavanCache.getProjectFile(projectId, APPLICATION_PROPERTIES_FILENAME);
                String devQuarkusVersion = devAppProps != null
                        ? codeService.getPropertyValue(devAppProps.getCode(), "camel.jbang.quarkusVersion") : null;
                if (ConfigService.inKubernetes()) {
                    String deploymentFragment = codeService.getDeploymentFragment(projectId);
                    kubernetesService.runDevModeContainer(projectId, verbose, compile, files, projectDevmodeImage, deploymentFragment, labels, envVars, devRuntime, devQuarkusVersion);
                } else if (configService.inDockerSwarmMode()) {
                    DockerStackService stack = getProjectDockerStackService(projectId);
                    dockerForKaravan.runProjectInDevMode(projectId, verbose, compile, stack, projectDevmodeImage, labels, envVars);
                } else {
                    DockerComposeService compose = getProjectDockerComposeService(projectId);
                    dockerForKaravan.runProjectInDevMode(projectId, verbose, compile, compose, files, projectDevmodeImage, labels, envVars);
                }
            } catch (Exception e) {
                // The dev-mode container failed to start. Remove the in-transit devmode
                // status so it doesn't linger as a phantom 'unknown' row that permanently
                // disables the Run button; the user can then retry.
                log.error("Failed to start dev mode for " + projectId, e);
                eventBus.publish(POD_CONTAINER_DELETED, JsonObject.mapFrom(status));
                throw e;
            }
            return projectId;
        } else {
            return null;
        }
    }

    public void buildProject(ProjectFolder projectFolder, String tag, String username) throws Exception {
        // Builds clone the project's own remote, so the project must have one and
        // the builder must run with the owner's credentials.
        ProjectFolder cached = karavanCache.getProject(projectFolder.getProjectId());
        if (cached == null || !gitService.hasRemote(cached)) {
            throw new Exception("Configure a Git repository for this project before building.");
        }
        if (cached.getGitOwner() != null && !Objects.equals(cached.getGitOwner(), username)) {
            throw new Exception("Only the Git owner (" + cached.getGitOwner() + ") can build this project.");
        }
        UserGitConfig gitUser = karavanCache.getUserGitConfig(username);
        Map<String, String> gitEnv = builderGitEnv(cached, gitUser);
        // The single build.sh dispatches its export + packaging on CAMEL_RUNTIME, so the
        // project's runtime (from its application.properties) must be injected into the build.
        String runtime = codeService.getProjectRuntime(projectFolder.getProjectId());

        tag = tag != null && !tag.isBlank()
                ? tag
                : Instant.now().toString().substring(0, 19).replace(":", "-");
        var name = projectFolder.getProjectId() + BUILDER_SUFFIX;
        var session = authService.createAndSaveSession(name, false);
        if (ConfigService.inKubernetes()) {
            String podFragment = codeService.getBuilderPodFragment();
            podFragment = codeService.substituteVariables(podFragment, Map.of("projectId", projectFolder.getProjectId(), "tag", tag));
            Map<String, String> env = new HashMap<>(gitEnv);
            env.put(ENV_VAR_BUILDER_SESSION_ID, session.getSessionId());
            env.put("CAMEL_RUNTIME", runtime);
            kubernetesService.runBuildProject(projectFolder.getProjectId(), podFragment, env);
        } else if (configService.inDockerSwarmMode()) {
            String stackFragment = codeService.getBuilderStackFragment(projectFolder.getProjectId(), tag);
            DockerStackService stack = DockerStackConverter.fromCode(stackFragment, name);
            stack.addEnvironment(ENV_VAR_RUN_IN_BUILD_MODE, "true");
            stack.addEnvironment(ENV_VAR_BUILDER_SESSION_ID, session.getSessionId());
            stack.addEnvironment("CAMEL_RUNTIME", runtime);
            gitEnv.forEach(stack::addEnvironment);
            dockerForKaravan.runBuildProject(projectFolder, stack, tag);
        } else {
            Map<String, String> sshFiles = codeService.getSshFiles();
            String script = codeService.getBuilderScript();
            String composeFragment = codeService.getBuilderComposeFragment(projectFolder.getProjectId(), tag);
            DockerComposeService compose = DockerComposeConverter.fromCode(composeFragment, name);
            compose.addEnvironment(ENV_VAR_RUN_IN_BUILD_MODE, "true");
            compose.addEnvironment(ENV_VAR_BUILDER_SESSION_ID, session.getSessionId());
            compose.addEnvironment("CAMEL_RUNTIME", runtime);
            gitEnv.forEach(compose::addEnvironment);
            dockerForKaravan.runBuildProject(projectFolder, script, compose, sshFiles, tag);
        }
    }

    /**
     * Git env injected into the build container so it can clone the project's own remote.
     */
    private Map<String, String> builderGitEnv(ProjectFolder projectFolder, UserGitConfig user) {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_REPOSITORY", projectFolder.getGitRepository());
        env.put("GIT_BRANCH", projectFolder.getGitBranch() != null ? projectFolder.getGitBranch() : "main");
        env.put("GIT_USERNAME", user != null && user.getGitUsername() != null ? user.getGitUsername() : "");
        env.put("GIT_PASSWORD", user != null && user.getGitToken() != null ? user.getGitToken() : "");
        return env;
    }

    public void importProject(String projectId) throws Exception {
        importProject(projectId, (UserGitConfig) null);
    }

    public void importProject(String projectId, String username) throws Exception {
        importProject(projectId, username != null ? karavanCache.getUserGitConfig(username) : null);
    }

    public void importProject(String projectId, UserGitConfig user) throws Exception {
        log.info("Import project from Git " + projectId);
        ProjectFolder projectFolder = karavanCache.getProject(projectId);
        if (projectFolder == null) {
            log.error("Cannot import unknown project: " + projectId);
            return;
        }
        List<PathCommitDetails> pathCommitDetails = gitService.readProjectFromRepository(projectFolder, user);
        importProjectFromRepo(pathCommitDetails, projectFolder);
    }

    private void importProjectFromRepo(List<PathCommitDetails> pathCommitDetails, ProjectFolder existing) {
        try {
            var folderDetails = pathCommitDetails.stream().filter(PathCommitDetails::isFolder).findFirst().orElse(null);
            var filesDetails = pathCommitDetails.stream().filter(f -> !f.isFolder()).toList();
            assert folderDetails != null;
            log.info("Import project from git repository {}", folderDetails.projectId());
            ProjectFolder projectFolder = getProjectFromRepo(folderDetails, pathCommitDetails);
            // Re-import rebuilds the folder from repo content, which has no git
            // remote info — carry the per-project remote forward from the cache.
            carryGitRemote(existing, projectFolder);
            ProjectFolderCommited projectFolderCommited = getProjectCommitedFromRepo(folderDetails);
            // PERSIST imported state: with per-project git there is NO startup
            // re-import — anything cache-only evaporates on the next restart/deploy
            // ("projects show empty"). The DB must mirror the cache.
            karavanCache.saveProject(projectFolder, true);
            karavanCache.saveProjectCommited(projectFolderCommited);
            karavanCache.deleteProjectFileCommited(projectFolder.getProjectId());
            filesDetails.forEach(repoFile -> {
                ProjectFile file = new ProjectFile(repoFile.fileName(), repoFile.content(), repoFile.projectId(), repoFile.commitTime());
                karavanCache.saveProjectFile(file, repoFile.commitId(), true);
            });
        } catch (Exception e) {
            log.error("Error during project import", e);
        }
    }

    public ProjectFolder getProjectFromRepo(PathCommitDetails folderDetails, List<PathCommitDetails> folderFiles) {
        String folderName = folderDetails.projectId();
        String propertiesFile = codeService.getPropertiesFile(folderFiles);
        if (propertiesFile != null) {
            String projectName = codeService.getProjectName(propertiesFile);
            return new ProjectFolder(folderName, projectName, folderDetails.commitTime());
        } else {
            return new ProjectFolder(folderName, folderName, folderDetails.commitTime());
        }
    }

    public ProjectFolderCommited getProjectCommitedFromRepo(PathCommitDetails folderDetails) {
        return new ProjectFolderCommited(folderDetails.projectId(), folderDetails.commitId(), folderDetails.commitTime());
    }

    /**
     * Preserve a project's configured Git remote + owner across a repo re-import.
     */
    private void carryGitRemote(ProjectFolder existing, ProjectFolder rebuilt) {
        if (existing == null || rebuilt == null) {
            return;
        }
        if (existing.getGitRepository() != null) {
            rebuilt.setGitRepository(existing.getGitRepository());
            rebuilt.setGitBranch(existing.getGitBranch());
            rebuilt.setGitOwner(existing.getGitOwner());
        }
        // Local-only audit fields are not stored in the repo — carry them too so
        // a re-import (post-push or import-on-create) keeps creator/access data.
        if (existing.getCreatedBy() != null) {
            rebuilt.setCreatedBy(existing.getCreatedBy());
            rebuilt.setCreatedAt(existing.getCreatedAt());
        }
    }

    public DockerComposeService getProjectDockerComposeService(String projectId) {
        String composeTemplate = codeService.getDockerComposeFileForProject(projectId);
        String composeCode = codeService.replaceEnvWithRuntimeProperties(composeTemplate);
        return DockerComposeConverter.fromCode(composeCode, projectId);
    }

    public DockerStackService getProjectDockerStackService(String projectId) {
        String stackTemplate = codeService.getDockerStackFileForProject(projectId);
        String stackCode = codeService.replaceEnvWithRuntimeProperties(stackTemplate);
        return DockerStackConverter.fromCode(stackCode, projectId);
    }

    private void modifyPropertyFileOnProjectCopy(ProjectFile propertyFile, ProjectFolder sourceProjectFolder, ProjectFolder projectFolder) {
        String fileContent = propertyFile.getCode();

        String sourceProjectIdProperty = String.format(PROPERTY_FORMATTER_PROJECT_ID, sourceProjectFolder.getProjectId());
        String sourceProjectNameProperty = String.format(PROPERTY_FORMATTER_PROJECT_NAME, sourceProjectFolder.getName());
        String sourceGavProperty = fileContent.lines().filter(line -> line.startsWith(PROPERTY_NAME_GAV)).findFirst().orElse("");

        String[] searchValues = {sourceProjectIdProperty, sourceProjectNameProperty, sourceGavProperty};

        String updatedProjectIdProperty = String.format(PROPERTY_FORMATTER_PROJECT_ID, projectFolder.getProjectId());
        String updatedProjectNameProperty = String.format(PROPERTY_FORMATTER_PROJECT_NAME, projectFolder.getName());
        String updatedGavProperty = String.format(codeService.getGavFormatter(), CodeService.getGavPackageSuffix(projectFolder.getProjectId()));

        String[] replacementValues = {updatedProjectIdProperty, updatedProjectNameProperty, updatedGavProperty};

        String updatedCode = StringUtils.replaceEach(fileContent, searchValues, replacementValues);

        propertyFile.setCode(updatedCode);
    }

    public void setProjectImage(String projectId, JsonObject data) {
        String imageName = data.getString("imageName");
        boolean commit = data.getBoolean("commit");
        data.put("projectId", projectId);
        data.put("fileNames", PROJECT_COMPOSE_FILENAME);
        codeService.updateDockerComposeImage(projectId, imageName);
        if (commit) {
            eventBus.publish(CMD_PUSH_PROJECT, data);
        }
    }

    public ProjectFolder create(ProjectFolder projectFolder) throws Exception {
        return create(projectFolder, false);
    }

    public ProjectFolder create(ProjectFolder projectFolder, boolean addSample) throws Exception {
        boolean projectIdExists = karavanCache.getProject(projectFolder.getProjectId()) != null;

        if (projectIdExists) {
            throw new Exception("Project with id " + projectFolder.getProjectId() + " already exists");
        } else {
            karavanCache.saveProject(projectFolder, true);
            // A project created with a remote that ALREADY holds this project
            // (created by Karavan earlier, later deleted locally — deletion never
            // touches the remote) is IMPORTED from the remote, not regenerated.
            if (gitService.hasRemote(projectFolder) && importFromRemoteIfPresent(projectFolder)) {
                ensureEssentialProjectFiles(karavanCache.getProject(projectFolder.getProjectId()));
                return karavanCache.getProject(projectFolder.getProjectId());
            }
            ProjectFile appProp = codeService.generateApplicationProperties(projectFolder);
            karavanCache.saveProjectFile(appProp, null, true);
            if (!ConfigService.inKubernetes()) {
                var port = getMaxPortMappedInProjects() + 1;
                ProjectFile projectCompose =
                        configService.inDockerSwarmMode()
                                ? codeService.createInitialProjectStack(projectFolder, port)
                                : codeService.createInitialProjectCompose(projectFolder, port);
                karavanCache.saveProjectFile(projectCompose, null, true);
            } else {
                ProjectFile projectDeployment = codeService.createInitialDeployment(projectFolder);
                karavanCache.saveProjectFile(projectDeployment, null, true);
            }
            if (addSample) {
                ProjectFile sample = codeService.createSampleIntegration(projectFolder);
                karavanCache.saveProjectFile(sample, null, true);
            }
        }
        return projectFolder;
    }

    public ProjectFolder updateProjectGit(String projectId, String gitRepository, String gitBranch, String username) throws Exception {
        ProjectFolder p = karavanCache.getProject(projectId);
        if (p == null) {
            throw new Exception("Project not found: " + projectId);
        }
        // The Git remote is restricted to the user who configured it.
        if (p.getGitOwner() != null && !Objects.equals(p.getGitOwner(), username)) {
            throw new SecurityException("Git remote is owned by " + p.getGitOwner() + " and cannot be changed by " + username);
        }
        boolean hasRepo = gitRepository != null && !gitRepository.isBlank();
        p.setGitRepository(hasRepo ? gitRepository.trim() : null);
        p.setGitBranch(hasRepo && gitBranch != null && !gitBranch.isBlank() ? gitBranch : null);
        p.setGitOwner(hasRepo ? username : null);
        karavanCache.saveProject(p, true);
        // Attaching a remote that already contains this project (e.g. re-attaching
        // the repo of a previously deleted project) imports its content — the
        // remote is the source of truth. An empty/new remote leaves local files
        // untouched (they get published on the first push).
        if (hasRepo && importFromRemoteIfPresent(p)) {
            ensureEssentialProjectFiles(karavanCache.getProject(projectId));
        }
        return karavanCache.getProject(projectId);
    }

    /**
     * A repo created outside Karavan (or holding a partial project) may lack the
     * files the platform itself needs. Without application.properties devmode
     * cannot resolve image/runtime, and without the deployment/compose file the
     * container cannot start (a null jkube fragment NPEd devmode on k8s). Never
     * overwrites imported content - only fills the gaps.
     */
    private void ensureEssentialProjectFiles(ProjectFolder projectFolder) {
        if (projectFolder == null) {
            return;
        }
        String projectId = projectFolder.getProjectId();
        if (karavanCache.getProjectFile(projectId, APPLICATION_PROPERTIES_FILENAME) == null) {
            log.info("Imported project {} has no application.properties - generating", projectId);
            karavanCache.saveProjectFile(codeService.generateApplicationProperties(projectFolder), null, true);
        }
        if (ConfigService.inKubernetes()) {
            if (karavanCache.getProjectFile(projectId, PROJECT_DEPLOYMENT_JKUBE_FILENAME) == null) {
                log.info("Imported project {} has no {} - generating", projectId, PROJECT_DEPLOYMENT_JKUBE_FILENAME);
                karavanCache.saveProjectFile(codeService.createInitialDeployment(projectFolder), null, true);
            }
        } else if (configService.inDockerSwarmMode()) {
            if (karavanCache.getProjectFile(projectId, PROJECT_STACK_FILENAME) == null) {
                karavanCache.saveProjectFile(codeService.createInitialProjectStack(projectFolder, getMaxPortMappedInProjects() + 1), null, true);
            }
        } else {
            if (karavanCache.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME) == null) {
                karavanCache.saveProjectFile(codeService.createInitialProjectCompose(projectFolder, getMaxPortMappedInProjects() + 1), null, true);
            }
        }
    }

    /**
     * Imports the project's files from its remote when the remote already has a
     * folder for this projectId. Returns false (and leaves the cache untouched)
     * when the remote is empty, unreachable or has no such project — callers then
     * proceed with locally generated/kept files.
     */
    private boolean importFromRemoteIfPresent(ProjectFolder projectFolder) {
        try {
            UserGitConfig user = projectFolder.getGitOwner() != null
                    ? karavanCache.getUserGitConfig(projectFolder.getGitOwner()) : null;
            List<PathCommitDetails> details = gitService.readProjectFromRepository(projectFolder, user);
            if (details.stream().anyMatch(d -> !d.isFolder())) {
                log.info("Remote already contains project {} - importing instead of generating files", projectFolder.getProjectId());
                importProjectFromRepo(details, projectFolder);
                return true;
            }
        } catch (Exception e) {
            // empty repo (no HEAD), missing repo, auth/network issues: not importable
            log.info("No importable content on remote for {}: {}", projectFolder.getProjectId(), e.getMessage());
        }
        return false;
    }

    public ProjectFolder copy(String sourceProjectId, ProjectFolder projectFolder) throws Exception {
        boolean projectIdExists = karavanCache.getProject(projectFolder.getProjectId()) != null;

        if (projectIdExists) {
            throw new Exception("Project with id " + projectFolder.getProjectId() + " already exists");
        } else {

            ProjectFolder sourceProjectFolder = karavanCache.getProject(sourceProjectId);

            // Save project
            karavanCache.saveProject(projectFolder, true);

            // Copy files from the source and make necessary modifications
            Map<String, ProjectFile> filesMap = karavanCache.getProjectFiles(sourceProjectId).stream()
                    .filter(f -> !Objects.equals(f.getName(), PROJECT_COMPOSE_FILENAME))
                    .filter(f -> !Objects.equals(f.getName(), PROJECT_STACK_FILENAME))
                    .filter(f -> !Objects.equals(f.getName(), PROJECT_DEPLOYMENT_JKUBE_FILENAME))
                    .collect(Collectors.toMap(
                            f -> GroupedKey.create(projectFolder.getProjectId(), DEV, f.getName()),
                            file -> {
                                var newFile = file.copy();
                                newFile.setProjectId(projectFolder.getProjectId());
                                if (Objects.equals(file.getName(), APPLICATION_PROPERTIES_FILENAME)) {
                                    modifyPropertyFileOnProjectCopy(newFile, sourceProjectFolder, projectFolder);
                                }
                                return newFile;
                            })
                    );

            karavanCache.saveProjectFiles(filesMap, true);

            if (!ConfigService.inKubernetes()) {
                ProjectFile projectCompose = null;
                var sourceComposeFile = karavanCache.getProjectFile(sourceProjectId, PROJECT_COMPOSE_FILENAME);
                if (sourceComposeFile != null) {
                    String newPort = String.valueOf(getMaxPortMappedInProjects() + 1);
                    var compose = DockerComposeConverter.fromCode(sourceComposeFile.getCode());
                    var service = compose.getServices().get(sourceProjectId);
                    service.setContainer_name(projectFolder.getProjectId());
                    service.setImage(projectFolder.getProjectId());
                    service.setPorts(service.getPorts().stream().map(s -> s.endsWith(":" + INTERNAL_PORT) ? newPort + ":" + INTERNAL_PORT : s).collect(Collectors.toList()));
                    compose.getServices().put(projectFolder.getProjectId(), service);
                    compose.getServices().remove(sourceProjectId);
                    projectCompose = new ProjectFile(PROJECT_COMPOSE_FILENAME, DockerComposeConverter.toCode(compose), projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
                } else {
                    projectCompose = codeService.createInitialProjectCompose(projectFolder, getMaxPortMappedInProjects() + 1);
                }
                karavanCache.saveProjectFile(projectCompose, null, true);
            } else {
                ProjectFile projectCompose = codeService.createInitialDeployment(projectFolder);
                karavanCache.saveProjectFile(projectCompose, null, true);
            }

            return projectFolder;
        }
    }

    public Integer getProjectPort(ProjectFile composeFile) {
        try {
            if (composeFile != null) {
                DockerComposeService dcs = DockerComposeConverter.fromCode(composeFile.getCode(), composeFile.getProjectId());
                Optional<Integer> port = dcs.getPortsMap().entrySet().stream()
                        .filter(e -> Objects.equals(e.getValue(), INTERNAL_PORT)).map(Map.Entry::getKey).findFirst();
                return port.orElse(null);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public int getMaxPortMappedInProjects() {
        try {
            List<ProjectFile> files = karavanCache.getProjectFilesByName(PROJECT_COMPOSE_FILENAME).stream()
                    .filter(f -> !Objects.equals(f.getProjectId(), ProjectFolder.Type.templates.name()))
                    .filter(f -> !Objects.equals(f.getProjectId(), ProjectFolder.Type.kamelets.name()))
                    .filter(f -> !Objects.equals(f.getProjectId(), ProjectFolder.Type.contracts.name()))
                    .filter(f -> !Objects.equals(f.getProjectId(), ProjectFolder.Type.configuration.name()))
                    .toList();
            if (!files.isEmpty()) {
                return files.stream().map(this::getProjectPort)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .max().orElse(INTERNAL_PORT);
            } else {
                return INTERNAL_PORT;
            }
        } catch (Exception e) {
            return INTERNAL_PORT;
        }
    }

}
