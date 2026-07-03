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
package org.apache.camel.karavan.http;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Corrects the cache headers for the Quinoa-served SPA.
 *
 * <p>The default static handler serves <b>everything</b> — including
 * {@code index.html} — as {@code public, immutable, max-age=86400}. That is right
 * for the content-hashed files under {@code /assets/} (their names change on every
 * build, so they may cache forever) but wrong for the {@code index.html} shell: a
 * cached-immutable index.html keeps pointing at the previous build's asset hashes
 * after a redeploy, so the browser requests now-deleted {@code /assets/<oldhash>}
 * files, the SPA fallback returns {@code index.html} ({@code text/html}), and the
 * browser blocks the CSS/JS on a MIME mismatch until the cache is cleared.
 *
 * <p>Fix: hashed assets stay long-lived and immutable; any {@code text/html}
 * response (the SPA shell, whatever route it was served for) is marked
 * {@code no-cache} so the browser revalidates it on every load and always picks up
 * a fresh deploy without a manual cache clear.
 */
@ApplicationScoped
public class StaticCacheFilter {

    static final String CACHE_CONTROL = "Cache-Control";
    static final String ASSETS_PREFIX = "/assets/";
    // Hashed assets never change under a given name -> cache for a year, immutable.
    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    // The HTML shell must be revalidated each load so a redeploy is picked up.
    static final String NO_CACHE = "no-cache";

    static void applyCacheControl(RoutingContext rc) {
        final MultiMap headers = rc.response().headers();
        final String path = rc.normalizedPath();
        if (path.startsWith(ASSETS_PREFIX)) {
            headers.set(CACHE_CONTROL, IMMUTABLE);
            return;
        }
        final String contentType = headers.get("Content-Type");
        if (contentType != null && contentType.startsWith("text/html")) {
            headers.set(CACHE_CONTROL, NO_CACHE);
        }
    }

    public void register(@Observes Filters filters) {
        // Register a headers-end handler so we run after the static/Quinoa handler
        // has set its own Cache-Control, and overwrite it deterministically.
        filters.register(rc -> {
            rc.addHeadersEndHandler(v -> applyCacheControl(rc));
            rc.next();
        }, 100);
    }
}
