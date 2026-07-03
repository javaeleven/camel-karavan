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
package org.apache.camel.karavan.api;

import io.quarkus.security.Authenticated;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.docker.StackToServiceSpecConverter;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.model.*;
import org.apache.camel.karavan.service.ConfigService;
import org.apache.camel.karavan.service.ProjectService;
import org.apache.camel.karavan.service.deployment.DeploymentService;

import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.KaravanConstants.*;
import static org.apache.camel.karavan.KaravanEvents.POD_CONTAINER_UPDATED;

@Slf4j
@Path("/ui/container")
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ContainerResource {

    private final EventBus eventBus;

    private final KaravanCache karavanCache;

    private final KubernetesService kubernetesService;

    private final DockerService dockerService;

    private final ProjectService projectService;

    private final DeploymentService deploymentService;

    private final KaravanConfig config;

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public List<PodContainerStatus> getAllContainerStatuses() throws Exception {
        return karavanCache.getPodContainerStatuses().stream()
                .sorted(Comparator.comparing(PodContainerStatus::getProjectId, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/{type}/{name}")
    public Response manageContainer(@PathParam("projectId") String projectId, @PathParam("type") String type, @PathParam("name") String name, JsonObject command) {
        try {
            ContainerCommand cmd = ContainerCommand.fromString(command.getString("command"));
            if (ConfigService.inKubernetes()) {
                if (cmd == ContainerCommand.delete) {
                    deploymentService.deleteWorkload(name);
                    return Response.ok().build();
                }
            } else if (cmd != null) {
                // set container statuses
                setContainerStatusTransit(projectId, name, type);
                // exec docker commands
                if (dockerService.isInSwarmMode()) {
                    switch (cmd) {
                        case deploy -> deployService(projectId, type, command);
                        case delete -> dockerService.deleteService(projectId);
                        default -> { /* not applicable in swarm mode */ }
                    }
                } else {
                    switch (cmd) {
                        case deploy -> deployContainer(projectId, type, command);
                        case run -> dockerService.runContainer(name);
                        case stop -> dockerService.stopContainer(name);
                        case pause -> dockerService.pauseContainer(name);
                        case delete -> deploymentService.deleteWorkload(name);
                    }
                }
                return Response.ok().build();
            }
            return Response.ok().build();
        } catch (Exception e) {
            var error = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            var result = "Error while executing command " + command + " on " + projectId + ": " + error;
            log.error(result);
            return Response.serverError().entity(result).build();
        }
    }

    public void deployContainer(String projectId, String type, JsonObject command) throws InterruptedException {
        if (Objects.equals(type, ContainerType.packaged.name())) {
            DockerComposeService dockerComposeService = projectService.getProjectDockerComposeService(projectId);
            if (dockerComposeService != null) {
                Map<String, String> labels = new HashMap<>();
                labels.put(LABEL_TYPE, ContainerType.packaged.name());
                labels.put(LABEL_CAMEL_RUNTIME, CamelRuntime.CAMEL_MAIN.getValue());
                labels.put(LABEL_PROJECT_ID, projectId);
                dockerService.createContainerFromCompose(dockerComposeService, labels, needPull(command));
                dockerService.runContainer(dockerComposeService.getContainer_name());
            }
        } else if (Objects.equals(type, ContainerType.devmode.name())) {
//                        TODO: merge with DevMode service
//                        dockerForKaravan.createDevmodeContainer(name, "");
//                        dockerService.runContainer(name);
        }
    }

    public void deployService(String projectId, String type, JsonObject command) throws InterruptedException {
        if (Objects.equals(type, ContainerType.packaged.name())) {
            DockerStackService stack = projectService.getProjectDockerStackService(projectId);
            if (stack != null) {
                Map<String, String> labels = new HashMap<>();
                labels.put(LABEL_TYPE, ContainerType.packaged.name());
                labels.put(LABEL_CAMEL_RUNTIME, CamelRuntime.CAMEL_MAIN.getValue());
                labels.put(LABEL_PROJECT_ID, projectId);
                stack.setLabels(labels);
                var serviceSpec = StackToServiceSpecConverter.convertService(projectId, stack);
                dockerService.createService(projectId, serviceSpec);
            }
        } else if (Objects.equals(type, ContainerType.devmode.name())) {
//                        TODO: merge with DevMode service
//                        dockerForKaravan.createDevmodeContainer(name, "");
//                        dockerService.runContainer(name);
        }
    }

    private DockerService.PULL_IMAGE needPull(JsonObject command) {
        try {
            return DockerService.PULL_IMAGE.valueOf(command.getString("pullImage"));
        } catch (Exception ignored) {
        }
        return DockerService.PULL_IMAGE.never;
    }

    private void setContainerStatusTransit(String projectId, String name, String type) {
        PodContainerStatus status = karavanCache.getPodContainerStatus(projectId, config.environment(), name);
        if (status == null) {
            status = PodContainerStatus.createByType(projectId, config.environment(), ContainerType.valueOf(type));
        }
        status.setInTransit(true);
        eventBus.publish(POD_CONTAINER_UPDATED, JsonObject.mapFrom(status));
    }

    @GET
    @Path("/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public List<PodContainerStatus> getContainerStatusesByProjectAndEnv(@PathParam("projectId") String projectId, @PathParam("env") String env) throws Exception {
        return karavanCache.getPodContainerStatuses(projectId, env).stream()
                .sorted(Comparator.comparing(PodContainerStatus::getContainerName))
                .collect(Collectors.toList());
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    @Path("/{projectId}/{type}/{name}")
    public Response deleteContainer(@PathParam("projectId") String projectId, @PathParam("type") String type, @PathParam("name") String name) {
        // set container statuses
        setContainerStatusTransit(projectId, name, type);
        try {
            deploymentService.deleteWorkload(name);
            return Response.accepted().build();
        } catch (Exception e) {
            log.error(e.getMessage());
            return Response.notModified().build();
        }
    }
}