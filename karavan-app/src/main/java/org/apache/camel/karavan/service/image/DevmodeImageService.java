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
package org.apache.camel.karavan.service.image;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.Ports;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.cloud.tools.jib.api.buildplan.Platform;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the devmode/build container image FROM SCRATCH in-app with the Jib Core
 * SDK and pushes it to the platform registry — no Docker daemon and no pre-built
 * image pipeline (the karavan-devmode Dockerfile/CI was removed). See
 * docs/DEVMODE_IMAGE_JIB.md.
 *
 * Assembly (mirrors the retired Dockerfile without RUN steps):
 *  - base {@code maven:3.9-eclipse-temurin-21} — already ships git + openssh-client,
 *    so the Dockerfile's apt-get was redundant;
 *  - JBang: the portable (java-based, arch-independent) distribution zip is
 *    downloaded once and layered at /karavan/.jbang;
 *  - JBang trust for the Apache Camel catalog is layered as trusted-sources.json
 *    (replaces the interactive `jbang trust add`);
 *  - /karavan dirs owned by uid 1001 (group 0, g+rwX) as before; user 1001:0;
 *  - env/CMD pin the platform's Camel version ({@code karavan.camel.version}).
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class DevmodeImageService {

    public enum Status { DISABLED, BUILDING, PUSHED, FAILED }

    static final String KARAVAN_HOME = "/karavan";
    static final String JBANG_DIR = KARAVAN_HOME + "/.jbang";
    static final String OWNERSHIP = "1001:0";
    // jbang parses this as a bare JSON array; trailing slash = trust the whole repo/org path.
    static final String TRUSTED_SOURCES = "[\"https://github.com/apache/camel/\"]";
    // jbang launcher needs java+mvn on PATH; base image paths are stable
    // (eclipse-temurin: /opt/java/openjdk, maven: /usr/bin/mvn symlink).
    static final String PATH_ENV = JBANG_DIR + "/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private final KaravanConfig config;

    @Getter
    private final AtomicReference<Status> status = new AtomicReference<>(Status.DISABLED);

    /** Async entry point (called from StartupLoader): never blocks or fails startup. */
    public void deriveAndPushIfEnabled() {
        if (!config.devmode().build().enabled()) {
            log.info("Devmode image build disabled (karavan.devmode.build.enabled=false)");
            return;
        }
        status.set(Status.BUILDING);
        CompletableFuture.runAsync(this::buildAndPush);
    }

    void buildAndPush() {
        String base = config.devmode().build().baseImage();
        String target = config.devmode().image();
        String camelVersion = config.camel().version();
        String jbangVersion = config.devmode().build().jbangVersion();
        try {
            log.info("Building devmode image {} from {} (Camel {}, JBang {})", target, base, camelVersion, jbangVersion);

            JibContainerBuilder builder = Jib.from(registryImage(base))
                    // Match the node architecture (jib defaults to amd64, which fails
                    // with "exec format error" on arm64 nodes). The app pod runs on
                    // the same nodes the devmode containers will.
                    .setPlatforms(java.util.Set.of(new Platform(hostArchitecture(), "linux")))
                    .addFileEntriesLayer(jbangLayer(jbangVersion))
                    .addFileEntriesLayer(karavanHomeLayer())
                    .setEnvironment(environment(camelVersion, jbangVersion))
                    .setLabels(Map.of(
                            "org.opencontainers.image.title", "karavan-devmode",
                            "org.opencontainers.image.description", "Apache Camel Karavan DevMode (built in-app by Jib Core)",
                            "org.opencontainers.image.url", "https://camel.apache.org",
                            "org.opencontainers.image.licenses", "Apache 2.0",
                            "org.opencontainers.image.base.name", base,
                            "org.apache.camel.karavan/camelVersion", camelVersion
                    ))
                    .setUser(OWNERSHIP)
                    .setWorkingDirectory(AbsoluteUnixPath.get(KARAVAN_HOME + "/code"))
                    .setExposedPorts(Ports.parse(List.of("8080")))
                    .setEntrypoint((List<String>) null)
                    .setProgramArguments(List.of(
                            "jbang",
                            "-Dcamel.jbang.version=" + camelVersion,
                            "-Dcamel.jbang.kameletsVersion=" + camelVersion,
                            "camel@apache/camel", "run",
                            "--source-dir=" + KARAVAN_HOME + "/code", "--console"
                    ));

            builder.containerize(
                    Containerizer.to(registryImage(target))
                            // local registries (registry:5000 / localhost:5005) are plain HTTP
                            .setAllowInsecureRegistries(true)
                            .addEventHandler(LogEvent.class, e -> {
                                if (e.getLevel() == LogEvent.Level.ERROR) {
                                    log.error("jib: {}", e.getMessage());
                                } else if (e.getLevel() == LogEvent.Level.LIFECYCLE) {
                                    log.info("jib: {}", e.getMessage());
                                }
                            }));

            status.set(Status.PUSHED);
            log.info("Devmode image {} pushed", target);
        } catch (Exception e) {
            status.set(Status.FAILED);
            // Same failure mode as a missing image: devmode runs surface a pull
            // error; the platform itself keeps running.
            log.error("Devmode image build failed for {}: {}", target, e.getMessage(), e);
        }
    }

    /** Portable JBang distribution unpacked to a layer at /karavan/.jbang. */
    private FileEntriesLayer jbangLayer(String jbangVersion) throws IOException, InterruptedException {
        Path dist = downloadJbang(jbangVersion);
        FileEntriesLayer.Builder layer = FileEntriesLayer.builder().setName("jbang");
        String zipRoot = "jbang-" + jbangVersion + "/";
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(dist))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith(zipRoot)) {
                    continue;
                }
                String rel = entry.getName().substring(zipRoot.length());
                Path tmp = Files.createTempFile("jbang-entry", null);
                Files.copy(zip, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                boolean executable = rel.startsWith("bin/");
                layer.addEntry(
                        tmp,
                        AbsoluteUnixPath.get(JBANG_DIR + "/" + rel),
                        executable ? FilePermissions.fromOctalString("755") : FilePermissions.fromOctalString("644"),
                        FileEntriesLayer.DEFAULT_MODIFICATION_TIME,
                        OWNERSHIP);
            }
        }
        // Non-interactive trust for the Apache Camel jbang catalog
        // (replaces `jbang trust add https://github.com/apache/camel`).
        Path trusted = Files.createTempFile("trusted-sources", ".json");
        Files.writeString(trusted, TRUSTED_SOURCES);
        layer.addEntry(trusted, AbsoluteUnixPath.get(JBANG_DIR + "/trusted-sources.json"),
                FilePermissions.fromOctalString("664"), FileEntriesLayer.DEFAULT_MODIFICATION_TIME, OWNERSHIP);
        return layer.build();
    }

    /** Writable /karavan working dirs, owned like the old useradd/chown/chmod g+rwX. */
    private FileEntriesLayer karavanHomeLayer() throws IOException {
        Path emptyDir = Files.createTempDirectory("karavan-empty");
        FileEntriesLayer.Builder layer = FileEntriesLayer.builder().setName("karavan-home");
        for (String dir : List.of("", "/.m2", "/code", "/code/kamelets", "/builder", "/.jbang", "/.jbang/bin", "/.jbang/cache")) {
            layer.addEntry(emptyDir, AbsoluteUnixPath.get(KARAVAN_HOME + dir),
                    FilePermissions.fromOctalString("775"), FileEntriesLayer.DEFAULT_MODIFICATION_TIME, OWNERSHIP);
        }
        return layer.build();
    }

    private Map<String, String> environment(String camelVersion, String jbangVersion) {
        Map<String, String> env = new LinkedHashMap<>();
        // uid 1001 has no /etc/passwd entry in the layered image, so HOME would
        // resolve to "/" (the retired Dockerfile's useradd set it): camel-jbang
        // creates ~/.camel and dies with AccessDeniedException without this.
        env.put("HOME", KARAVAN_HOME);
        env.put("JBANG_VERSION", jbangVersion);
        env.put("CAMEL_VERSION", camelVersion);
        env.put("JBANG_JAVA_OPTIONS", "-Dcamel.jbang.version=" + camelVersion
                + " -Dcamel.jbang.kameletsVersion=" + camelVersion);
        env.put("KARAVAN", KARAVAN_HOME);
        env.put("JBANG_DIR", JBANG_DIR);
        env.put("MAVEN_CONFIG", KARAVAN_HOME + "/.m2");
        env.put("JBANG_REPO", KARAVAN_HOME + "/.m2");
        env.put("BUILDER_PATH", KARAVAN_HOME + "/builder");
        env.put("CODE_DIR", KARAVAN_HOME + "/code");
        env.put("KAMELETS_DIR", KARAVAN_HOME + "/code/kamelets");
        env.put("PATH", PATH_ENV);
        return env;
    }

    /** Download the portable (arch-independent) JBang dist zip, cached across runs. */
    private Path downloadJbang(String version) throws IOException, InterruptedException {
        Path cached = Path.of(System.getProperty("java.io.tmpdir"), "jbang-" + version + ".zip");
        if (Files.exists(cached) && Files.size(cached) > 0) {
            return cached;
        }
        String url = "https://github.com/jbangdev/jbang/releases/download/v" + version + "/jbang-" + version + ".zip";
        log.info("Downloading JBang {} from {}", version, url);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("JBang download failed: HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    static String hostArchitecture() {
        String arch = System.getProperty("os.arch", "amd64").toLowerCase();
        return (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";
    }

    private RegistryImage registryImage(String ref) throws Exception {
        RegistryImage image = RegistryImage.named(ref);
        var username = config.containerImage().registryUsername();
        var password = config.containerImage().registryPassword();
        // Credentials target the platform registry; public bases (docker.io) pull
        // anonymously — jib only presents credentials to the registry that asks.
        if (username.isPresent() && !username.get().isBlank()
                && password.isPresent() && !password.get().isBlank()) {
            image.addCredential(username.get(), password.get());
        }
        return image;
    }
}
