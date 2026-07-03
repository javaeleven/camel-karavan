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
package org.apache.camel.karavan.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.service.ConfigService;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Default
@Readiness
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class KubernetesStatusService implements HealthCheck {

    protected static final int INFORMERS = 3;

    private final KaravanConfig config;

    private final EventBus eventBus;
    List<SharedIndexInformer> informers = new ArrayList<>(INFORMERS);
    private String namespace;

    @Produces
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    void onStart(@Observes StartupEvent ev) throws Exception {
        if (ConfigService.inKubernetes()) {
            log.info("Status Listeners: starting...");
            startInformers();
            log.info("Status Listeners: started");
        }
    }

    void onStop(@Observes ShutdownEvent ev) throws IOException {
        if (ConfigService.inKubernetes()) {
            log.info("Status Listeners: stopping...");
            stopInformers();
            log.info("Status Listeners: stopped");
        }
    }

    public void startInformers() {
        try {
            stopInformers();
            log.info("Starting Kubernetes Informers");

            KubernetesClient client = kubernetesClient();

            SharedIndexInformer<Deployment> deploymentInformer = client.apps().deployments().inNamespace(getNamespace()).inform();
            deploymentInformer.addEventHandlerWithResyncPeriod(new DeploymentEventHandler(this, eventBus), 30 * 1000L);
            informers.add(deploymentInformer);

            SharedIndexInformer<Service> serviceInformer = client.services().inNamespace(getNamespace()).inform();
            serviceInformer.addEventHandlerWithResyncPeriod(new ServiceEventHandler(this, eventBus), 30 * 1000L);
            informers.add(serviceInformer);

            SharedIndexInformer<Pod> podRunInformer = client.pods().inNamespace(getNamespace()).inform();
            podRunInformer.addEventHandlerWithResyncPeriod(new PodEventHandler(this, eventBus), 30 * 1000L);
            informers.add(podRunInformer);

            log.info("Started Kubernetes Informers");
        } catch (Exception e) {
            log.error("Error starting informers: " + e.getMessage());
        }
    }


    @Override
    public HealthCheckResponse call() {
        if (ConfigService.inKubernetes()) {
            if (informers.size() == INFORMERS) {
                return HealthCheckResponse.named("Kubernetes").up().build();
            }
        }
        return HealthCheckResponse.named("Kubernetes").down().build();
    }

    public void stopInformers() {
        log.info("Stop Kubernetes Informers");
        informers.forEach(SharedIndexInformer::close);
        informers.clear();
    }


    public String getNamespace() {
        if (namespace == null) {
            try (KubernetesClient client = kubernetesClient()) {
                namespace = LaunchMode.current().getProfileKey().equalsIgnoreCase("dev") ? "karavan" : client.getNamespace();
            }
        }
        return namespace;
    }

    public Deployment getDeployment(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            return client.apps().deployments().inNamespace(getNamespace()).withName(name).get();
        }
    }

    public String getCluster() {
        try (KubernetesClient client = kubernetesClient()) {
            return client.getMasterUrl().getHost();
        }
    }

    public ResourceRequirements getResourceRequirements(Map<String, String> containerResources) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(containerResources.get("requests.cpu")))
                .addToRequests("memory", new Quantity(containerResources.get("requests.memory")))
                .addToLimits("cpu", new Quantity(containerResources.get("limits.cpu")))
                .addToLimits("memory", new Quantity(containerResources.get("limits.memory")))
                .build();
    }

    public String getEnvironment() {
        return config.environment();
    }
}
