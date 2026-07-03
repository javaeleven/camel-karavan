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
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.service.ConfigService;
import org.apache.camel.karavan.model.UserGitConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Secrets & ConfigMaps management for the System page. The SPA's SystemApi calls
 * these /platform/system/* paths with base64-encoded names/keys; they wire to the
 * existing KubernetesService methods. Without this resource the System "Secrets"
 * and "ConfigMaps" tabs never receive data and spin on loading forever.
 * <p>
 * NOTE: /platform must be in quarkus.quinoa.ignored-path-prefixes so these are
 * routed to the backend rather than served the SPA shell.
 */
@Path("/platform/system")
@Authenticated
public class SystemResource extends AbstractApiResource {

    @Inject
    KubernetesService kubernetesService;

    private static String dec(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    // ---- Per-user Git credentials ----
    // Stored per authenticated user; the token is write-only (never returned to
    // the browser). The UI surfaces these in the System page "Git" tab; they are
    // used to authenticate per-project Git operations (fetch branches/clone/push).

    @GET
    @Path("/git")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getGitConfig() {
        String username = getIdentity().getString("username");
        UserGitConfig config = karavanCache.getUserGitConfig(username);
        boolean hasToken = config != null && config.getGitToken() != null && !config.getGitToken().isBlank();
        return Response.ok(JsonObject.of()
                .put("gitUsername", config != null ? config.getGitUsername() : null)
                .put("hasToken", hasToken)).build();
    }

    @POST
    @Path("/git")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveGitConfig(JsonObject body) {
        String username = getIdentity().getString("username");
        if (username == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String gitUsername = body.getString("gitUsername");
        String gitToken = body.getString("gitToken");
        // Empty token on save = keep the existing one (the UI never echoes it back).
        if (gitToken == null || gitToken.isBlank()) {
            UserGitConfig existing = karavanCache.getUserGitConfig(username);
            gitToken = existing != null ? existing.getGitToken() : null;
        }
        karavanCache.saveUserGitConfig(new UserGitConfig(username, gitUsername, gitToken), true);
        return Response.ok(JsonObject.of("gitUsername", gitUsername, "hasToken", gitToken != null && !gitToken.isBlank())).build();
    }

    /**
     * Secrets/ConfigMaps are Kubernetes-only. On Docker the UI still calls these
     * tabs' endpoints — answer empty/204 instead of hitting the k8s client (which
     * throws "namespace cannot be null" when no cluster is present).
     */
    private boolean notInKubernetes() {
        return !ConfigService.inKubernetes();
    }

    // ---- Secrets ----

    @GET
    @Path("/secrets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSecrets() {
        if (notInKubernetes()) {
            return Response.ok(List.of()).build();
        }
        return Response.ok(kubernetesService.getSecrets()).build();
    }

    @POST
    @Path("/secrets/{name}")
    public Response createSecret(@PathParam("name") String name) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Secrets are Kubernetes-only").build();
        }
        kubernetesService.createSecret(dec(name));
        return Response.ok().build();
    }

    @DELETE
    @Path("/secrets/{name}")
    public Response deleteSecret(@PathParam("name") String name) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Secrets are Kubernetes-only").build();
        }
        kubernetesService.deleteSecret(dec(name));
        return Response.noContent().build();
    }

    @GET
    @Path("/secrets/{name}/{key}")
    public Response getSecretValue(@PathParam("name") String name, @PathParam("key") String key) {
        if (notInKubernetes()) {
            return Response.noContent().build();
        }
        return Response.ok(kubernetesService.getSecretValue(dec(name), dec(key))).build();
    }

    @POST
    @Path("/secrets/{name}/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setSecretValue(@PathParam("name") String name, @PathParam("key") String key, String value) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Secrets are Kubernetes-only").build();
        }
        kubernetesService.setSecretValue(dec(name), dec(key), value);
        return Response.ok().build();
    }

    @DELETE
    @Path("/secrets/{name}/{key}")
    public Response deleteSecretValue(@PathParam("name") String name, @PathParam("key") String key) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Secrets are Kubernetes-only").build();
        }
        kubernetesService.deleteSecretValue(dec(name), dec(key));
        return Response.noContent().build();
    }

    // ---- ConfigMaps ----

    @GET
    @Path("/configmaps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfigMaps() {
        if (notInKubernetes()) {
            return Response.ok(List.of()).build();
        }
        return Response.ok(kubernetesService.getConfigMaps()).build();
    }

    @POST
    @Path("/configmaps/{name}")
    public Response createConfigMap(@PathParam("name") String name) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("ConfigMaps are Kubernetes-only").build();
        }
        kubernetesService.createConfigMap(dec(name));
        return Response.ok().build();
    }

    @DELETE
    @Path("/configmaps/{name}")
    public Response deleteConfigMap(@PathParam("name") String name) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("ConfigMaps are Kubernetes-only").build();
        }
        kubernetesService.deleteConfigMap(dec(name));
        return Response.noContent().build();
    }

    @GET
    @Path("/configmaps/{name}/{key}")
    public Response getConfigMapValue(@PathParam("name") String name, @PathParam("key") String key) {
        if (notInKubernetes()) {
            return Response.noContent().build();
        }
        String n = dec(name), k = dec(key);
        String value = kubernetesService.getConfigMaps().stream()
                .filter(cm -> Objects.equals(cm.getName(), n))
                .findFirst()
                .map(cm -> cm.getData() != null ? cm.getData().get(k) : null)
                .orElse(null);
        return Response.ok(value).build();
    }

    @POST
    @Path("/configmaps/{name}/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setConfigMapValue(@PathParam("name") String name, @PathParam("key") String key, String value) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("ConfigMaps are Kubernetes-only").build();
        }
        kubernetesService.setConfigMapValue(dec(name), dec(key), value);
        return Response.ok().build();
    }

    @DELETE
    @Path("/configmaps/{name}/{key}")
    public Response deleteConfigMapValue(@PathParam("name") String name, @PathParam("key") String key) {
        if (notInKubernetes()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity("ConfigMaps are Kubernetes-only").build();
        }
        kubernetesService.deleteConfigMapValue(dec(name), dec(key));
        return Response.noContent().build();
    }
}
