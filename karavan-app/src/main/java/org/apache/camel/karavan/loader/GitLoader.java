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
package org.apache.camel.karavan.loader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.model.ProjectFile;
import org.apache.camel.karavan.model.ProjectFolder;
import org.apache.camel.karavan.service.CodeService;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Objects;

import static org.apache.camel.karavan.KaravanConstants.DEV;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class GitLoader {

    private final KaravanConfig config;

    private final KaravanCache karavanCache;

    private final CodeService codeService;

    public void load() throws Exception {
        // No global Git repository: projects are hydrated from Postgres (CacheLoader)
        // and each is synced to its own remote on demand by its owner. Here we only
        // seed the built-in projects (kamelets/templates/...) from bundled code.
        log.info("Starting Project service (per-project git; no global repository)");
        if (Objects.equals(config.environment(), DEV)) {
            addKameletsProject();
            addBuildInProject(ProjectFolder.Type.templates.name());
            addBuildInProject(ProjectFolder.Type.configuration.name());
            // build.sh is system build-scaffolding: keep the persisted copy in sync
            // with the bundled one so build fixes (e.g. the mandatory --runtime flag)
            // reach installs whose build.sh was seeded by an earlier version.
            codeService.refreshConfigurationFile(CodeService.BUILD_SCRIPT_FILENAME);
            addBuildInProject(ProjectFolder.Type.documentation.name());
            addBuildInProject(ProjectFolder.Type.contracts.name());
        }
    }

    void addKameletsProject() {
        try {
            ProjectFolder kamelets = karavanCache.getProject(ProjectFolder.Type.kamelets.name());
            if (kamelets == null) {
                log.info("Add custom kamelets project");
                kamelets = new ProjectFolder(ProjectFolder.Type.kamelets.name(), "Custom Kamelets", Instant.now().getEpochSecond() * 1000L, ProjectFolder.Type.kamelets);
                karavanCache.saveProject(kamelets, false);
            }
        } catch (Exception e) {
            log.error("Error during custom kamelets project creation", e);
        }
    }

    public void addBuildInProject(String projectId) {
        try {
            ProjectFolder projectFolder = karavanCache.getProject(projectId);
            if (projectFolder == null) {
                var title = projectId.length() < 5 ? projectId.toUpperCase() : StringUtils.capitalize(projectId);
                projectFolder = new ProjectFolder(projectId, title, Instant.now().getEpochSecond() * 1000L, ProjectFolder.Type.valueOf(projectId));
                karavanCache.saveProject(projectFolder, false);

                codeService.getBuildInProjectFiles(projectId).forEach((name, value) -> {
                    ProjectFile file = new ProjectFile(name, value, projectId, Instant.now().getEpochSecond() * 1000L);
                    karavanCache.saveProjectFile(file, null, false);
                });
            } else {
                codeService.getBuildInProjectFiles(projectId).forEach((name, value) -> {
                    ProjectFile f = karavanCache.getProjectFile(projectId, name);
                    if (f == null) {
                        ProjectFile file = new ProjectFile(name, value, projectId, Instant.now().getEpochSecond() * 1000L);
                        karavanCache.saveProjectFile(file, null, false);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error during creation of project " + projectId, e);
        }
    }
}
