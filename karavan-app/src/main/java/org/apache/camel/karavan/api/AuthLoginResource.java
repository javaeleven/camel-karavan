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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Browser-facing endpoint that starts the OIDC (BFF) login. It is
 * {@code @Authenticated}, so hitting it without a session makes Quarkus OIDC
 * (application-type=web-app) run the Authorization Code flow — a full-page
 * redirect to the IdP. After the callback + restore-path, the request reaches
 * this method authenticated and we 302 the browser back to the SPA.
 * <p>
 * The SPA navigates here (e.g. /auth/login?returnTo=/projects) whenever it gets
 * a 401 from a /ui/* call in OIDC mode, instead of trying to do the redirect in
 * JavaScript — which is what caused the /login <-> IdP loop.
 */
@Path("/auth/login")
@Authenticated
public class AuthLoginResource {

    // Only allow same-origin absolute paths to avoid an open redirect.
    static String safeReturnTo(String raw) {
        if (raw == null || raw.isBlank()) return "/";
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
            return "/";
        }
        // Never return to the login page or auth endpoints: those re-enter the
        // login flow and cause a /login <-> /auth/login redirect loop. Send the
        // freshly-authenticated user to the app root instead.
        if (raw.equals("/login") || raw.startsWith("/login?")
                || raw.equals("/auth") || raw.startsWith("/auth/")) {
            return "/";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) return "/";
        }
        return raw;
    }

    @GET
    public Response login(@QueryParam("returnTo") String returnTo) {
        return Response.seeOther(URI.create(safeReturnTo(returnTo))).build();
    }
}
