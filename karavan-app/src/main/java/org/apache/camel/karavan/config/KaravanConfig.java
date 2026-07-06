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
package org.apache.camel.karavan.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Optional;

/**
 * Typed, validated view of all {@code karavan.*} configuration (replaces the
 * {@code @ConfigProperty} fields that were scattered across ~25 classes).
 * Method names map to kebab-case keys (SmallRye default); defaults mirror the
 * previous per-field {@code defaultValue}s exactly. Validation runs at startup,
 * so a misconfigured deployment fails fast instead of NPE-ing later.
 */
@ConfigMapping(prefix = "karavan")
public interface KaravanConfig {

    /**
     * Deployment environment name ("dev", "test", "prod", ...) — not Quarkus profiles.
     */
    @WithDefault("dev")
    @NotBlank
    String environment();

    /**
     * Key is camelCase in application.properties, hence the explicit name.
     */
    @WithName("appName")
    @WithDefault("karavan")
    @NotBlank
    String appName();

    @NotBlank
    String title();

    @NotBlank
    String version();

    Optional<List<String>> environments();

    @WithDefault("camel-main")
    @NotBlank
    String defaultRuntime();

    Optional<String> gav();

    Optional<String> privateKeyPath();

    Optional<String> knownHostsPath();

    /**
     * Explicit OpenShift flag (otherwise auto-detected by the Kubernetes client).
     */
    Optional<Boolean> openshift();

    Shared shared();

    Secret secret();

    Camel camel();

    Devmode devmode();

    Builder builder();

    ContainerImage containerImage();

    Docker docker();

    Container container();

    Keycloak keycloak();

    Datasource datasource();

    interface Shared {
        Optional<String> folder();
    }

    interface Secret {
        @WithDefault("karavan")
        @NotBlank
        String name();
    }

    interface Camel {
        /**
         * Default Camel version seeded into new projects (camel.jbang.camelVersion).
         */
        @WithDefault("4.18.2")
        @NotBlank
        String version();

        /**
         * Default Quarkus version seeded into new quarkus-runtime projects.
         */
        @WithDefault("3.27.0")
        @NotBlank
        String quarkusVersion();

        Status status();

        /**
         * Camel status poll interval — consumed by @Scheduled expressions.
         */
        interface Status {
            @WithDefault("5s")
            String interval();
        }
    }

    /**
     * Container status/statistics poll intervals — consumed by @Scheduled expressions.
     */
    interface Container {
        Status status();

        Statistics statistics();

        interface Status {
            @WithDefault("off")
            String interval();
        }

        interface Statistics {
            @WithDefault("off")
            String interval();
        }
    }

    /**
     * Local-dev Keycloak defaults; interpolated into quarkus.oidc.* in application.properties.
     */
    interface Keycloak {
        Optional<String> url();

        Optional<String> realm();

        Backend backend();

        interface Backend {
            /**
             * Key is camelCase in application.properties, hence the explicit name.
             */
            @WithName("clientId")
            Optional<String> clientId();

            Optional<String> secret();
        }
    }

    /**
     * Local datasource defaults; interpolated into quarkus.datasource.* / quarkus.flyway.*.
     */
    interface Datasource {
        Optional<String> username();

        Optional<String> password();

        Optional<String> url();

        Optional<String> schemas();
    }

    interface Devmode {
        @NotBlank
        String image();

        /**
         * Key is camelCase in application.properties, hence the explicit name.
         */
        @WithName("withImagePullPolicy")
        @WithDefault("IfNotPresent")
        Optional<String> imagePullPolicy();

        /**
         * Attach a per-project .m2 volume/PVC to devmode/build containers.
         */
        @WithDefault("false")
        Optional<Boolean> createm2();

        /**
         * Auto-reload a RUNNING devmode container when project files change
         * (save/rename/delete in the UI, or a git pull) — debounced, so bursts
         * of editor auto-saves coalesce into one reload.
         */
        @WithName("hot-reload")
        @WithDefault("true")
        boolean hotReload();

        Service service();

        Build build();

        interface Service {
            @NotBlank
            String account();
        }

        /**
         * In-app derivation of the devmode image with Jib Core (no Docker daemon):
         * base image + platform env/versions/labels, pushed to devmode.image()
         * using the container-image registry credentials. See docs/DEVMODE_IMAGE_JIB.md.
         */
        interface Build {
            @WithDefault("true")
            boolean enabled();

            /** Plain tooling base: ships maven + JDK 21 + git; JBang is layered in-app. */
            @WithDefault("maven:3.9-eclipse-temurin-21")
            @NotBlank
            String baseImage();

            /** Portable JBang distribution layered into the image. */
            @WithDefault("0.139.3")
            @NotBlank
            String jbangVersion();
        }
    }

    interface Builder {
        Service service();

        interface Service {
            @NotBlank
            String account();
        }
    }

    interface ContainerImage {
        @NotBlank
        String registry();

        @NotBlank
        String group();

        Optional<String> registryUsername();

        Optional<String> registryPassword();
    }

    interface Docker {
        @WithDefault("karavan")
        @NotBlank
        String network();
    }
}
