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

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.service.ConfigService;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.camel.karavan.KaravanEvents.NOTIFICATION_PROJECTS_STARTED;

@Slf4j
@Default
@Readiness
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class StartupLoader implements HealthCheck {

    private final KaravanConfig config;

    private final DockerService dockerService;

    private final EventBus eventBus;

    private final CacheLoader cacheLoader;

    private final org.apache.camel.karavan.cache.KaravanCache karavanCache;

    private final org.apache.camel.karavan.service.image.DevmodeImageService devmodeImageService;

    private final GitLoader gitLoader;

    private final AtomicBoolean ready = new AtomicBoolean(false);

    @Override
    public HealthCheckResponse call() {
        if (ready.get()) {
            return HealthCheckResponse.named("Projects").up().build();
        } else {
            return HealthCheckResponse.named("Projects").down().build();
        }
    }

    void onStart(@Observes StartupEvent ev) throws Exception {
        log.info("Starting " + ConfigService.getAppName() + " in " + config.environment() + " env in " + (ConfigService.inKubernetes() ? "Kubernetes" : "Docker"));
        if (!ConfigService.inKubernetes() && !dockerService.checkDocker()) {
            Quarkus.asyncExit();
        } else {
            createCaches();
        }
    }

    void createCaches() {
        try {
            log.info("Loading projects ...");
            cacheLoader.load();
            gitLoader.load();
            log.info("Projects loaded");
            eventBus.publish(NOTIFICATION_PROJECTS_STARTED, null);
            seedBuiltInRoles();
            // Async, non-blocking: derive+push the devmode image in-app (Jib Core)
            // when enabled — replaces the pre-built-image pipeline.
            devmodeImageService.deriveAndPushIfEnabled();
            ready.set(true);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Seed the built-in platform roles (names only) so the Access page lists them
     * on a fresh install; users come from the IdP / manual management.
     */
    private void seedBuiltInRoles() {
        org.apache.camel.karavan.KaravanConstants.getAllRoles().forEach(name -> {
            if (karavanCache.getRole(name) == null) {
                karavanCache.saveRole(new org.apache.camel.karavan.model.AccessRole(name, name.replace('-', ' ')), true);
            }
        });
    }
}
