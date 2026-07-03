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
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.service.ConfigService;

import static org.apache.camel.karavan.KaravanEvents.*;

@Slf4j
@Default
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ConfigListener {

    private final ConfigService configService;

    private final EventBus eventBus;

    @ConsumeEvent(value = NOTIFICATION_PROJECTS_STARTED, blocking = true)
    public void shareOnStartup(String data) throws Exception {
        configService.shareOnStartup();
    }

    @ConsumeEvent(value = CMD_SHARE_CONFIGURATION, blocking = true, ordered = true)
    public void shareConfig(JsonObject event) throws Exception {
        String filename = event.getString("filename");
        String userId = event.getString("userId");
        log.info("Config share event: for " + (filename != null ? filename : "all"));
        try {
            configService.share(filename);
            eventBus.publish(NOTIFICATION_CONFIG_SHARED, JsonObject.of("userId", userId, "className", "filename", "filename", filename));
        } catch (Exception e) {
            var error = e.getCause() != null ? e.getCause() : e;
            log.error("Failed to share configuration", error);
            if (userId != null) {
                eventBus.publish(NOTIFICATION_ERROR, JsonObject.of(
                        "userId", userId,
                        "className", filename,
                        "error", "Failed to share configuration: " + e.getMessage())
                );
            }
        }
    }
}
