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
package org.apache.camel.karavan.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerService;
import org.apache.camel.karavan.kubernetes.KubernetesService;
import org.apache.camel.karavan.model.Configuration;
import org.apache.camel.karavan.model.ProjectFile;
import org.apache.camel.karavan.model.ProjectFolder;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.camel.karavan.KaravanConstants.DEV;
import static org.apache.camel.karavan.service.CodeService.BUILD_SCRIPT_FILENAME;

@Slf4j
@ApplicationScoped
public class ConfigService {

    private static Boolean inKubernetes;
    private static Boolean inDocker;
    private static Boolean inDockerSwarmMode;
    // Typed, validated view of all karavan.* configuration.
    @Inject
    KaravanConfig config;
    @Inject
    KaravanCache karavanCache;
    @Inject
    KubernetesService kubernetesService;
    @Inject
    DockerService dockerService;
    @Inject
    CodeService codeService;
    private Configuration configuration;

    public static boolean inKubernetes() {
        if (inKubernetes == null) {
            inKubernetes = Objects.nonNull(System.getenv("KUBERNETES_SERVICE_HOST"));
        }
        return inKubernetes;
    }

    public static boolean inDocker() {
        if (inDocker == null) {
            inDocker = !inKubernetes() && Files.exists(Paths.get("/.dockerenv"));
        }
        return inDocker;
    }

    public static String getAppName() {
        return ConfigProvider.getConfig().getOptionalValue("karavan.appName", String.class).orElse("karavan");
    }

    void onStart(@Observes @Priority(10) StartupEvent ev) {
        getConfiguration(null);
    }

    public Configuration getConfiguration(Map<String, String> advanced) {
        if (configuration == null) {
            var configFilenames = codeService.getBuildInProjectFileList(ProjectFolder.Type.configuration.name());
            configuration = new Configuration(
                    config.title(),
                    config.version(),
                    inKubernetes() ? "kubernetes" : "docker",
                    inDockerSwarmMode(),
                    config.environment(),
                    config.secret().name(),
                    config.secret().name(),
                    getEnvs(),
                    configFilenames,
                    advanced
            );
            configuration.setDefaultRuntime(config.defaultRuntime());
        }
        return configuration;
    }

    public boolean inDockerSwarmMode() {
        if (inKubernetes()) {
            // no Docker socket in a pod — probing it just logs a connection error
            return false;
        }
        if (inDockerSwarmMode == null) {
            inDockerSwarmMode = dockerService.isInSwarmMode();
        }
        return inDockerSwarmMode;
    }

    public void shareOnStartup() {
        if (ConfigService.inKubernetes() && config.environment().equals(DEV)) {
            log.info("Creating Configmap for {}", BUILD_SCRIPT_FILENAME);
            try {
                share(BUILD_SCRIPT_FILENAME);
            } catch (Exception e) {
                var error = e.getCause() != null ? e.getCause() : e;
                log.error("Error while trying to share build.sh as Configmap", error);
            }
        }
    }

    public void share(String filename) throws Exception {
        if (filename != null) {
            ProjectFile f = karavanCache.getProjectFile(ProjectFolder.Type.configuration.name(), filename);
            if (f != null) {
                shareFile(f);
            }
        } else {
            for (ProjectFile f : karavanCache.getProjectFiles(ProjectFolder.Type.configuration.name())) {
                shareFile(f);
            }
        }
    }

    private void shareFile(ProjectFile f) throws Exception {
        var filename = f.getName();
        var parts = filename.split("\\.");
        var prefix = parts[0];
        if (config.environment().equals(DEV) && !getEnvs().contains(prefix)) { // no prefix AND dev env
            storeFile(f.getName(), f.getCode());
        } else if (Objects.equals(prefix, config.environment())) { // with prefix == env
            filename = f.getName().substring(config.environment().length() + 1);
            storeFile(filename, f.getCode());
        }
    }

    private void storeFile(String filename, String code) throws Exception {
        if (inKubernetes()) {
            kubernetesService.createConfigmap(filename, Map.of(filename, code));
        } else if (inDockerSwarmMode()) {
            dockerService.createConfig(filename, code);
        } else {
            if (config.shared().folder().isPresent()) {
                Files.writeString(Paths.get(config.shared().folder().get(), filename), code);
            } else {
                throw new Exception("Shared folder not configured");
            }
        }
    }

    protected List<String> getEnvs() {
        return config.environments().orElse(List.of(DEV));
    }


}