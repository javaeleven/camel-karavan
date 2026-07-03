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
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.service.ProjectService;

import java.util.Map;

import static org.apache.camel.karavan.KaravanEvents.CMD_DELETE_INTEGRATION;
import static org.apache.camel.karavan.KaravanEvents.CMD_RELOAD_PROJECT_CODE;

@Slf4j
@Path("/ui/devmode")
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class DevModeResource {

    private final KaravanConfig config;

    private final KaravanCache karavanCache;

    private final ProjectService projectService;

    private final EventBus eventBus;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Authenticated
    @Path("/run/{projectId}/{verbose}/{compile}")
    public Response runProjectInDeveloperMode(@PathParam("projectId") String projectId, @PathParam("verbose") boolean verbose, @PathParam("compile") boolean compile) {
        try {
            String containerName = projectService.runProjectInDeveloperMode(projectId, verbose, compile, Map.of(), Map.of(), false);
            if (containerName != null) {
                return Response.ok(containerName).build();
            } else {
                return Response.notModified().build();
            }
        } catch (Exception e) {
            log.error(e.getMessage());
            e.printStackTrace();
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/reload/{projectId}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response reload(@PathParam("projectId") String projectId) {
        eventBus.publish(CMD_RELOAD_PROJECT_CODE, projectId);
        return Response.ok().build();
    }

    @DELETE
    @Path("/{projectId}/{deletePVC}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response deleteDevMode(@PathParam("projectId") String projectId, @PathParam("deletePVC") boolean deletePVC) {
        eventBus.publish(CMD_DELETE_INTEGRATION, projectId);
        return Response.accepted().build();
    }
}