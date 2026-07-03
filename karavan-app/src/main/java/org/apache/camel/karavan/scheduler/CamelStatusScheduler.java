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
package org.apache.camel.karavan.scheduler;

import io.quarkus.scheduler.Scheduled;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.model.CamelRuntime;
import org.apache.camel.karavan.model.CamelStatusRequest;
import org.apache.camel.karavan.model.ContainerType;
import org.apache.camel.karavan.service.ConfigService;

import java.util.Map;
import java.util.Objects;

import static org.apache.camel.karavan.KaravanConstants.CAMEL_PREFIX;
import static org.apache.camel.karavan.KaravanConstants.LABEL_KUBERNETES_RUNTIME;
import static org.apache.camel.karavan.KaravanEvents.CMD_COLLECT_CAMEL_STATUS;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class CamelStatusScheduler {

    private final KaravanCache karavanCache;

    private final KaravanConfig config;

    private final EventBus eventBus;

    // Camel status is collected for every supported runtime, not just camel-main, so
    // quarkus/spring-boot integrations also report route/context metrics to the UI.
    private static boolean isSupportedCamelRuntime(String runtime) {
        return Objects.equals(runtime, CamelRuntime.CAMEL_MAIN.getValue())
                || Objects.equals(runtime, CamelRuntime.QUARKUS.getValue())
                || Objects.equals(runtime, CamelRuntime.SPRING_BOOT.getValue());
    }

    @Scheduled(every = "{karavan.camel.status.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void collectCamelStatuses() {
        log.debug("Collect Camel Statuses");
        if (ConfigService.inKubernetes()) {
            karavanCache.getPodContainerStatuses(config.environment()).stream()
                    .filter(cs -> Objects.equals(cs.getLabels().get(LABEL_KUBERNETES_RUNTIME), CAMEL_PREFIX))
                    .filter(cs -> Objects.equals(cs.getType(), ContainerType.devmode) || Objects.equals(cs.getType(), ContainerType.packaged))
                    .filter(cs -> isSupportedCamelRuntime(cs.getCamelRuntime()))
                    .forEach(cs -> {
                        CamelStatusRequest csr = new CamelStatusRequest(cs.getProjectId(), cs.getContainerName());
                        eventBus.publish(CMD_COLLECT_CAMEL_STATUS,
                                JsonObject.mapFrom(Map.of("containerStatus", cs, "camelStatusRequest", csr))
                        );
                    });
        } else {
            karavanCache.getPodContainerStatuses(config.environment()).stream()
                    .filter(Objects::nonNull)
                    .filter(cs -> isSupportedCamelRuntime(cs.getCamelRuntime()))
                    .filter(cs -> Objects.equals(cs.getType(), ContainerType.devmode) || Objects.equals(cs.getType(), ContainerType.packaged))
                    .forEach(cs -> {
                        CamelStatusRequest csr = new CamelStatusRequest(cs.getProjectId(), cs.getContainerName());
                        eventBus.publish(CMD_COLLECT_CAMEL_STATUS,
                                JsonObject.mapFrom(Map.of("containerStatus", cs, "camelStatusRequest", csr))
                        );
                    });
        }
    }
}
