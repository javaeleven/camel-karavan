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
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.DeploymentStatus;

import java.util.Objects;

import static org.apache.camel.karavan.KaravanEvents.*;

@ApplicationScoped
public class DeploymentStatusListener {

    @Inject
    KaravanCache karavanCache;

    @Inject
    EventBus eventBus;

    @ConsumeEvent(value = DEPLOYMENT_DELETED, blocking = true, ordered = true)
    public void cleanDeploymentStatus(JsonObject data) {
        DeploymentStatus ds = data.mapTo(DeploymentStatus.class);
        karavanCache.deleteDeploymentStatus(ds);
        karavanCache.deleteCamelStatuses(ds.getProjectId(), ds.getEnv());
        notifyStatusUpdated(ds.getProjectId(), ds.getEnv());
    }

    @ConsumeEvent(value = DEPLOYMENT_UPDATED, blocking = true, ordered = true)
    public void saveDeploymentStatus(JsonObject data) {
        DeploymentStatus ds = data.mapTo(DeploymentStatus.class);
        DeploymentStatus old = karavanCache.getDeploymentStatus(ds.getProjectId(), ds.getEnv());
        karavanCache.saveDeploymentStatus(ds);
        // Push only when the rollout state actually moves (new deployment or a change in
        // desired/ready/unavailable replicas) so the UI tracks rollout progress live
        // without refreshing on every no-op informer resync.
        boolean changed = old == null
                || !Objects.equals(old.getReplicas(), ds.getReplicas())
                || !Objects.equals(old.getReadyReplicas(), ds.getReadyReplicas())
                || !Objects.equals(old.getUnavailableReplicas(), ds.getUnavailableReplicas());
        if (changed) {
            notifyStatusUpdated(ds.getProjectId(), ds.getEnv());
        }
    }

    private void notifyStatusUpdated(String projectId, String env) {
        eventBus.publish(NOTIFICATION_STATUS_UPDATED, new JsonObject()
                .put("type", "deployment")
                .put("projectId", projectId)
                .put("env", env));
    }
}