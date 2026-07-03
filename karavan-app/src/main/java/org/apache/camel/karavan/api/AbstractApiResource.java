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

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.service.AuthService;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Objects;

public class AbstractApiResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    KaravanCache karavanCache;

    protected JsonObject getIdentity() {
        if (identity == null || identity.isAnonymous()) {
            return JsonObject.of()
                    .put("email", null)
                    .put("username", null)
                    .put("firstName", null)
                    .put("lastName", null)
                    .put("roles", new java.util.ArrayList<>());
        }

        String username = identity.getPrincipal().getName();
        var user = karavanCache.getUser(username);

        var roles = new JsonArray(new java.util.ArrayList<>(identity.getRoles()));

        // In OIDC mode the principal is not in the local cache; the principal name
        // is the email (quarkus.oidc.token.principal-claim=email), so fall back to
        // it. This keeps "email" non-null for the git author identity (PersonIdent),
        // which otherwise throws "E-mail address of PersonIdent must not be null".
        String email = (user != null && user.getEmail() != null) ? user.getEmail() : username;

        // In OIDC mode the principal is the ID token (roles.source=idtoken); the
        // "profile" scope carries the person's name claims. Session principals
        // (build containers) are plain and have none.
        String firstName = null;
        String lastName = null;
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            firstName = jwt.getClaim("given_name");
            lastName = jwt.getClaim("family_name");
        }

        return JsonObject.of()
                .put("email", email)
                .put("username", username)
                .put("firstName", firstName)
                .put("lastName", lastName)
                .put("roles", roles);
    }

    /**
     * Project write authorization: a project may be managed by its creator, users it
     * is assigned to, its git owner, or a platform admin. Projects with no recorded
     * creator/owner (legacy or imported) stay unrestricted for compatibility.
     * Throws 403 otherwise.
     */
    protected void requireProjectWriteAccess(String projectId) {
        var project = karavanCache.getProject(projectId);
        if (project == null) {
            return; // nothing to protect; endpoint decides how to handle a missing project
        }
        if (identity != null && !identity.isAnonymous() && identity.hasRole(AuthService.ROLE_ADMIN)) {
            return;
        }
        String username = (identity != null && !identity.isAnonymous()) ? identity.getPrincipal().getName() : null;
        boolean unrestricted = project.getCreatedBy() == null && project.getGitOwner() == null;
        boolean assigned = project.getAssignedUsers() != null && project.getAssignedUsers().contains(username);
        if (unrestricted
                || Objects.equals(project.getCreatedBy(), username)
                || Objects.equals(project.getGitOwner(), username)
                || assigned) {
            return;
        }
        throw new WebApplicationException("Project " + projectId + " can only be managed by its creator, assignees or an admin",
                Response.Status.FORBIDDEN);
    }
}