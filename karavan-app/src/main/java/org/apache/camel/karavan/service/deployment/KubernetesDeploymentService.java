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
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import org.apache.camel.karavan.kubernetes.KubernetesService;

/**
 * Kubernetes flavour of {@link DeploymentService}: workloads are pods.
 */
@ApplicationScoped
// Injectable only by concrete type: the interface is served solely by the producer.
@Typed(KubernetesDeploymentService.class)
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class KubernetesDeploymentService implements DeploymentService {

    private final KubernetesService kubernetesService;

    @Override
    public void deleteWorkload(String name) {
        kubernetesService.deletePod(name);
    }

    @Override
    public String infrastructure() {
        return "kubernetes";
    }
}
