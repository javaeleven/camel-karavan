/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.model.PodContainerStatus;

import java.util.concurrent.ConcurrentHashMap;

import static org.apache.camel.karavan.KaravanEvents.CMD_RELOAD_PROJECT_CODE;

/**
 * Hot reload for devmode: when project files change (UI save/rename/delete or a
 * git pull) and the project's devmode container is RUNNING, push the new code
 * into it automatically — the same CMD_RELOAD_PROJECT_CODE flow as the manual
 * Reload button (upload files + /q/dev/reload).
 *
 * Changes are DEBOUNCED per project: editors auto-save every few hundred ms
 * while typing, so the reload fires once, {@link #DEBOUNCE_MS} after the last
 * change, instead of hammering the container per keystroke.
 *
 * Disabled via karavan.devmode.hot-reload=false.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class DevModeHotReloadService {

    private static final long DEBOUNCE_MS = 1500;
    private static final String RUNNING = "running";

    private final KaravanConfig config;
    private final KaravanCache karavanCache;
    private final EventBus eventBus;
    private final Vertx vertx;

    private final ConcurrentHashMap<String, Long> pendingTimers = new ConcurrentHashMap<>();

    /**
     * Notify that a project's files changed. Cheap and safe to call on every
     * save: it no-ops unless hot reload is enabled AND the project has a
     * running devmode container.
     */
    public void projectFilesChanged(String projectId) {
        if (projectId == null || !config.devmode().hotReload()) {
            return;
        }
        if (!hasRunningDevModeContainer(projectId)) {
            return;
        }
        pendingTimers.compute(projectId, (id, previousTimer) -> {
            if (previousTimer != null) {
                vertx.cancelTimer(previousTimer);
            }
            return vertx.setTimer(DEBOUNCE_MS, t -> fire(id));
        });
    }

    private void fire(String projectId) {
        pendingTimers.remove(projectId);
        // Re-check: the container may have stopped during the debounce window
        if (hasRunningDevModeContainer(projectId)) {
            log.info("Hot reload: pushing changed files to devmode container {}", projectId);
            eventBus.publish(CMD_RELOAD_PROJECT_CODE, projectId);
        }
    }

    private boolean hasRunningDevModeContainer(String projectId) {
        PodContainerStatus status = karavanCache.getDevModePodContainerStatus(projectId, config.environment());
        return status != null && RUNNING.equals(status.getState());
    }
}
