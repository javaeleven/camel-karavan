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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.karavan.KaravanConstants;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.cache.PodContainerStatus;
import org.apache.camel.karavan.listener.CamelStatusListener;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class CamelDebugService {

    private static final Logger LOGGER = Logger.getLogger(CamelDebugService.class.getName());

    private static final int DEFAULT_TIMEOUT = 2000;

    @ConfigProperty(name = "karavan.environment", defaultValue = KaravanConstants.DEV)
    String environment;

    @Inject
    KaravanCache karavanCache;

    @Inject
    CamelStatusListener camelStatusListener;

    public String sendCommand(String projectId, String env, String queryString) throws Exception {
        String targetEnv = env != null ? env : environment;
        PodContainerStatus podContainerStatus = karavanCache.getDevModePodContainerStatus(projectId, targetEnv);
        if (podContainerStatus == null) {
            throw new Exception("No devmode container found for project " + projectId);
        }
        String address = camelStatusListener.getContainerAddressForStatus(podContainerStatus);
        String url = address + "/q/dev/debug?" + queryString;
        try {
            return camelStatusListener.getResult(url, DEFAULT_TIMEOUT);
        } catch (InterruptedException | ExecutionException ex) {
            LOGGER.warn("sendCommand " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
            throw ex;
        }
    }

    public String getState(String projectId, String env) throws Exception {
        String targetEnv = env != null ? env : environment;
        PodContainerStatus podContainerStatus = karavanCache.getDevModePodContainerStatus(projectId, targetEnv);
        if (podContainerStatus == null) {
            throw new Exception("No devmode container found for project " + projectId);
        }
        String address = camelStatusListener.getContainerAddressForStatus(podContainerStatus);
        String url = address + "/q/dev/debug";
        try {
            return camelStatusListener.getResult(url, DEFAULT_TIMEOUT);
        } catch (InterruptedException | ExecutionException ex) {
            LOGGER.warn("getState " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
            throw ex;
        }
    }
}
