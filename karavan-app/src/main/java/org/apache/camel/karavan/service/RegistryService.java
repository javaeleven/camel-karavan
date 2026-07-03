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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.model.RegistryConfig;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class RegistryService {

    private final KaravanConfig config;

    private final KubernetesService kubernetesService;

    public RegistryConfig getRegistryConfig() {
        String registryUrl = config.containerImage().registry();
        String imageGroup = config.containerImage().group();
        String registryUsername = config.containerImage().registryUsername().orElse(null);
        String registryPassword = config.containerImage().registryPassword().orElse(null);
        if (ConfigService.inKubernetes()) {
            registryUrl = kubernetesService.getKaravanSecret("image-registry");
            String i = kubernetesService.getKaravanSecret("image-group");
            imageGroup = i != null ? i : config.containerImage().group();
            registryUsername = kubernetesService.getKaravanSecret("image-registry-username");
            registryPassword = kubernetesService.getKaravanSecret("image-registry-password");
        }
        return new RegistryConfig(registryUrl, imageGroup, registryUsername, registryPassword);
    }

    public String getRegistryWithGroupForSync() {
        String registryUrl = config.containerImage().registry();
        if (!ConfigService.inKubernetes() && registryUrl.equalsIgnoreCase("registry:5000")) {
            registryUrl = "localhost:5555";
        }
        return registryUrl + "/" + config.containerImage().group();
    }
}
