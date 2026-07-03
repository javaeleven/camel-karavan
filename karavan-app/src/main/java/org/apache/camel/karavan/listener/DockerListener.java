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

package org.apache.camel.karavan.listener;

import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.model.PodContainerStatus;
import org.apache.camel.karavan.service.CodeService;

import java.util.Map;
import java.util.Objects;

import static org.apache.camel.karavan.KaravanEvents.*;
import static org.apache.camel.karavan.service.CodeService.BUILD_SCRIPT_FILENAME;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class DockerListener {

    private final KaravanConfig config;

    private final DockerService dockerService;

    private final CodeService codeService;

    private final KaravanCache karavanCache;

    private final EventBus eventBus;

    @ConsumeEvent(value = CMD_PULL_IMAGES, blocking = true)
    void loadImagesForProject(JsonObject event) {
        String projectId = event.getString("projectId");
        log.info("Pull image for project: " + projectId);
        String userId = event.getString("userId");
        try {
            dockerService.pullImagesForProject(projectId);
            eventBus.publish(NOTIFICATION_IMAGES_LOADED, event);
        } catch (Exception e) {
            var error = "Failed to load images " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            log.error(error);
            eventBus.publish(NOTIFICATION_ERROR, JsonObject.of("userId", userId, "className", "image", "error", error)
            );
        }
    }

    @ConsumeEvent(value = CMD_COPY_CODE_TO_CONTAINER_IN_SWARM, blocking = true)
    void copyCodeToContainerInSwarmMode(JsonObject event) {
        var projectId = event.getString("projectId");
        var containerId = event.getString("containerId");
        var type = event.getString("type");
        var statuses = karavanCache.getPodContainerStatuses(projectId, config.environment());
        var status = statuses != null && !statuses.isEmpty() ? statuses.getFirst() : null;
        try {
            status = status != null ? status : PodContainerStatus.createDevMode(projectId, config.environment());
            if (!status.getCodeLoaded()) {
                if (Objects.equals(type, "devmode")) {
                    Map<String, String> files = codeService.getProjectFilesForDevMode(projectId, true);
                    log.info("Copy files: " + files.size());
                    dockerService.copyFiles(containerId, "/karavan/code", files, true);
                    dockerService.copyFiles(containerId, "/tmp", Map.of(".karavan.done", "done"), true);
                } else if (Objects.equals(type, "build")) {
                    Map<String, String> sshFiles = codeService.getSshFiles();
                    String script = codeService.getBuilderScript();
                    log.info("Copy build script: " + script.length() + " and ssh files: " + sshFiles.size());
                    dockerService.copyExecFile(containerId, "/karavan/builder", BUILD_SCRIPT_FILENAME, script);
                    sshFiles.forEach((name, text) -> {
                        dockerService.copyExecFile(containerId, "/karavan/.ssh", name, text);
                    });
                }
                status.setCodeLoaded(true);
                karavanCache.savePodContainerStatus(status);
            }
        } catch (Exception e) {
            var error = "Failed to load images " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            log.error(error);
        }
    }
}
