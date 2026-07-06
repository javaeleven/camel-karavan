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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.model.ProjectFolder;
import org.apache.camel.karavan.service.DevModeHotReloadService;
import org.apache.camel.karavan.model.UserGitConfig;
import org.apache.camel.karavan.service.GitService;
import org.apache.camel.karavan.service.ProjectService;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.apache.camel.karavan.KaravanEvents.CMD_PUSH_PROJECT;

@Slf4j
@Path("/ui/git")
public class ProjectGitResource extends AbstractApiResource {

    @Inject
    DevModeHotReloadService devModeHotReloadService;

    @Inject
    ProjectService projectService;

    @Inject
    GitService gitService;

    @Inject
    EventBus eventBus;

    @POST
    @Authenticated
    @Path("/branches")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response branches(JsonObject body) {
        String repository = body != null ? body.getString("repository") : null;
        if (repository == null || repository.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Repository URL is required.").build();
        }
        String username = getIdentity().getString("username");
        UserGitConfig config = karavanCache.getUserGitConfig(username);
        String gitToken = config != null ? config.getGitToken() : null;
        boolean hasCreds = gitToken != null && !gitToken.isBlank();
        try {
            List<String> branches = gitService.listRemoteBranches(repository.trim(),
                    config != null ? config.getGitUsername() : null, gitToken);
            return Response.ok(JsonObject.of("branches", branches)).build();
        } catch (Exception e) {
            String raw = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.error("Error listing remote branches for {} (user={}, hasCreds={}): {}",
                    repository, username, hasCreds, raw);
            String msg;
            if (!hasCreds) {
                msg = "No Git credentials found for your account. Add your Git username + token in "
                        + "System → Git, then Fetch again (required for private repositories).";
            } else if (raw != null && (raw.contains("Authentication") || raw.contains("not authorized")
                    || raw.contains("401") || raw.contains("403"))) {
                msg = "Git authentication failed. Check your token in System → Git and that you have "
                        + "access to " + repository + ".";
            } else if (raw != null && (raw.contains("not found") || raw.contains("Repository not found")
                    || raw.contains("ENOENT") || raw.contains("unknown host") || raw.contains("UnknownHost"))) {
                msg = "Repository not reachable: " + repository + ". Check the URL.";
            } else {
                msg = "Could not read branches from " + repository + ": " + raw;
            }
            return Response.status(Response.Status.BAD_REQUEST).entity(msg).build();
        }
    }

    @POST
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HashMap<String, String> push(HashMap<String, String> params, @Context SecurityContext securityContext) throws Exception {
        requireGitOwner(params.get("projectId"));
        var identity = getIdentity();
        var data = JsonObject.mapFrom(params);
        data.put("authorName", identity.getString("username"));
        data.put("authorEmail", identity.getString("email"));
        eventBus.publish(CMD_PUSH_PROJECT, data);
        return params;
    }

    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{projectId}")
    @Authenticated
    public Response pull(@PathParam("projectId") String projectId) {
        try {
            requireGitOwner(projectId);
            projectService.importProject(projectId, getIdentity().getString("username"));
            // pulled files land in the cache — hot-reload a running devmode container
            devModeHotReloadService.projectFilesChanged(projectId);
            return Response.ok().build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    /**
     * A project's Git remote is restricted to the user who configured it. Throws
     * 400 if the project has no remote, 403 if the caller is not the owner.
     */
    private ProjectFolder requireGitOwner(String projectId) {
        ProjectFolder p = projectId != null ? karavanCache.getProject(projectId) : null;
        if (p == null || !gitService.hasRemote(p)) {
            throw new WebApplicationException("Project has no Git repository configured", Response.Status.BAD_REQUEST);
        }
        String username = getIdentity().getString("username");
        if (p.getGitOwner() != null && !Objects.equals(p.getGitOwner(), username)) {
            throw new WebApplicationException("Git remote is restricted to " + p.getGitOwner(), Response.Status.FORBIDDEN);
        }
        return p;
    }
}