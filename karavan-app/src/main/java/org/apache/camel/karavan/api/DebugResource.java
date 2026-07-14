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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.service.CamelDebugService;
import org.jboss.logging.Logger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Path("/ui/debug")
public class DebugResource {

    private static final Logger LOGGER = Logger.getLogger(DebugResource.class.getName());

    @Inject
    CamelDebugService camelDebugService;

    @POST
    @Path("/enable/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response enable(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        try {
            String result = camelDebugService.sendCommand(projectId, env, "command=enable");
            return Response.ok(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/disable/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response disable(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        try {
            String result = camelDebugService.sendCommand(projectId, env, "command=disable");
            return Response.ok(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/breakpoint/{projectId}/{env}/{nodeId}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBreakpoint(@PathParam("projectId") String projectId, @PathParam("env") String env, @PathParam("nodeId") String nodeId) {
        try {
            String query = "command=add&breakpoint=" + encode(nodeId);
            String result = camelDebugService.sendCommand(projectId, env, query);
            return Response.accepted(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/breakpoint/{projectId}/{env}/{nodeId}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeBreakpoint(@PathParam("projectId") String projectId, @PathParam("env") String env, @PathParam("nodeId") String nodeId) {
        try {
            String query = "command=remove&breakpoint=" + encode(nodeId);
            String result = camelDebugService.sendCommand(projectId, env, query);
            return Response.accepted(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/step/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response step(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        try {
            String result = camelDebugService.sendCommand(projectId, env, "command=step");
            return Response.accepted(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("/resume/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response resume(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        try {
            String result = camelDebugService.sendCommand(projectId, env, "command=resume");
            return Response.accepted(result).build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/state/{projectId}/{env}")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState(@PathParam("projectId") String projectId, @PathParam("env") String env) {
        try {
            String result = camelDebugService.getState(projectId, env);
            if (result != null) {
                return Response.ok(result).build();
            } else {
                return Response.noContent().build();
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
