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
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.model.*;
import org.apache.camel.karavan.service.ConfigService;
import org.apache.camel.karavan.service.ProjectService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

@Slf4j
@Path("/ui/project")
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ProjectResource extends AbstractApiResource {

    private final KaravanCache karavanCache;

    private final KubernetesService kubernetesService;

    private final DockerService dockerService;


    private final DevModeResource devModeResource;

    private final ContainerResource containerResource;

    private final InfrastructureResource infrastructureResource;

    private final ProjectService projectService;

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProjectFolder> getAll(@QueryParam("type") String type, @Context SecurityContext ctx) {
        return projectService.getAllProjects(type);
    }

    @GET
    @Authenticated
    @Path("/commited/all")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ProjectFolderCommited> getAllCommited() {
        return karavanCache.getFoldersCommited();
    }

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{projectId}")
    public ProjectFolder get(@PathParam("projectId") String projectId) throws Exception {
        return karavanCache.getProject(projectId);
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@Valid ProjectFolder projectFolder, @QueryParam("sample") boolean sample) {
        try {
            // A configured Git remote is owned by its creator (restricted to him).
            String username = getIdentity().getString("username");
            if (username != null && projectFolder.getGitRepository() != null && !projectFolder.getGitRepository().isBlank()) {
                projectFolder.setGitOwner(username);
            } else {
                projectFolder.setGitOwner(null);
            }
            // Audit: record the creator; feeds the project write-access check.
            projectFolder.setCreatedBy(username);
            projectFolder.setCreatedAt(java.time.Instant.now().toEpochMilli());
            return Response.ok(projectService.create(projectFolder, sample)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{project}")
    public void delete(@PathParam("project") String project, @QueryParam("deleteContainers") boolean deleteContainers, @Context SecurityContext securityContext) throws Exception {
        String projectId = URLDecoder.decode(project, StandardCharsets.UTF_8);
        ProjectFolder projectFolder = karavanCache.getProject(projectId);
        if (projectFolder == null) {
            // Already gone — deletion is idempotent, return 204 instead of NPE-ing.
            log.info("Project " + projectId + " already deleted");
            return;
        }
        // Project-level authorization: creator/assignees/admin only (legacy projects
        // with no recorded creator stay unrestricted).
        requireProjectWriteAccess(projectId);
        // Container/deployment cleanup is best-effort: a missing or unreachable
        // container must not abort (and 500) the project deletion.
        if (deleteContainers) {
            try {
                log.info("Deleting containers and deployments");
                devModeResource.deleteDevMode(projectId, true);
                containerResource.deleteContainer(projectId, ContainerType.devmode.name(), projectId);
                containerResource.deleteContainer(projectId, ContainerType.packaged.name(), projectId);
                infrastructureResource.deleteDeployment(null, projectId);
            } catch (Exception e) {
                log.warn("Container/deployment cleanup failed for " + projectId + ": " + e.getMessage());
            }
        }
        // Deletion is LOCAL-ONLY (cache + database). The remote git repository is
        // intentionally never touched: it keeps the full history, so the project
        // can be re-imported later by attaching the same repository again.
        karavanCache.getProjectFiles(projectId).forEach(file -> karavanCache.deleteProjectFile(projectId, file.getName()));
        karavanCache.getProjectFilesCommited(projectId).forEach(file -> karavanCache.deleteProjectFileCommited(projectId, file.getName()));
        karavanCache.deleteProject(projectId);
        karavanCache.deleteProjectCommited(projectId);
        log.info("Project {} deleted from Karavan (remote git untouched)", projectId);
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/build/{tag}")
    public Response build(ProjectFolder projectFolder, @PathParam("tag") String tag) throws Exception {
        try {
            projectService.buildProject(projectFolder, tag, getIdentity().getString("username"));
            return Response.ok().entity(projectFolder).build();
        } catch (Exception e) {
            log.error(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/build/{env}/{buildName}")
    public Response deleteBuild(@PathParam("env") String env, @PathParam("buildName") String buildName) {
        buildName = URLDecoder.decode(buildName, StandardCharsets.UTF_8);
        if (ConfigService.inKubernetes()) {
            kubernetesService.deletePod(buildName);
            return Response.ok().build();
        } else {
            dockerService.deleteContainer(buildName);
            return Response.ok().build();
        }
    }

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/status/camel/{projectId}/{env}")
    public Response getCamelStatusForProjectAndEnv(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        List<CamelStatus> statuses = karavanCache.getCamelStatusesByProjectAndEnv(projectId, env)
                .stream().filter(Objects::nonNull).peek(camelStatus -> {
                    var stats = List.copyOf(camelStatus.getStatuses()).stream().filter(s -> !Objects.equals(s.getName(), CamelStatusValue.Name.trace)).toList();
                    camelStatus.setStatuses(stats);
                }).toList();
        if (!statuses.isEmpty()) {
            return Response.ok(statuses).build();
        } else {
            return Response.noContent().build();
        }
    }

    @GET
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/traces/{projectId}/{env}")
    public Response getCamelTracesForProjectAndEnv(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        List<CamelStatus> statuses = karavanCache.getCamelStatusesByProjectAndEnv(projectId, env)
                .stream().peek(camelStatus -> {
                    var stats = List.copyOf(camelStatus.getStatuses()).stream().filter(s -> Objects.equals(s.getName(), CamelStatusValue.Name.trace)).toList();
                    camelStatus.setStatuses(stats);
                }).toList();
        if (!statuses.isEmpty()) {
            return Response.ok(statuses).build();
        } else {
            return Response.noContent().build();
        }
    }

    @PUT
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/git")
    public Response updateGit(@PathParam("projectId") String projectId, ProjectFolder body) {
        try {
            var updated = projectService.updateProjectGit(projectId, body.getGitRepository(), body.getGitBranch(), getIdentity().getString("username"));
            return Response.ok(updated).build();
        } catch (SecurityException e) {
            return Response.status(Response.Status.FORBIDDEN).entity(e.getMessage()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/copy/{sourceProject}")
    public Response copy(@PathParam("sourceProject") String sourceProject, ProjectFolder projectFolder) {
        try {
            // Same server-side stamping as create(): ownership and audit fields
            // come from the authenticated identity, never from the client payload.
            String username = getIdentity().getString("username");
            if (username != null && projectFolder.getGitRepository() != null && !projectFolder.getGitRepository().isBlank()) {
                projectFolder.setGitOwner(username);
            } else {
                projectFolder.setGitOwner(null);
            }
            projectFolder.setCreatedBy(username);
            projectFolder.setCreatedAt(java.time.Instant.now().toEpochMilli());
            return Response.ok(projectService.copy(sourceProject, projectFolder)).build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}