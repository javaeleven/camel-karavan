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
package org.apache.camel.karavan.service.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.apache.camel.karavan.service.ConfigService;

/**
 * Selects the {@link DeploymentService} implementation for the runtime
 * infrastructure (decided once at startup by {@link ConfigService#inKubernetes()}).
 * Inject the interface — never a concrete implementation.
 */
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class DeploymentServiceProducer {

    private final KubernetesDeploymentService kubernetesDeploymentService;
    private final DockerDeploymentService dockerDeploymentService;

    @Produces
    @ApplicationScoped
    public DeploymentService deploymentService() {
        return ConfigService.inKubernetes() ? kubernetesDeploymentService : dockerDeploymentService;
    }
}
