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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.docker.DockerComposeConverter;
import org.apache.camel.karavan.model.*;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.KaravanConstants.*;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class CodeService {

    public static final String APPLICATION_PROPERTIES_FILENAME = "application.properties";
    public static final String PROJECT_COMPOSE_FILENAME = "docker-compose.yaml";
    public static final String PROJECT_STACK_FILENAME = "docker-stack.yaml";
    public static final String MARKDOWN_EXTENSION = ".md";
    public static final String PROJECT_JKUBE_EXTENSION = ".jkube.yaml";
    public static final String PROJECT_DEPLOYMENT_JKUBE_FILENAME = "deployment" + PROJECT_JKUBE_EXTENSION;
    public static final int INTERNAL_PORT = 8080;
    public static final String BUILD_SCRIPT_FILENAME = "build.sh";
    public static final String JSON_EXTENSION = ".json";
    public static final String YAML_EXTENSION = ".yaml";
    public static final String GROOVY_EXTENSION = ".groovy";
    public static final String CAMEL_YAML_EXTENSION = ".camel.yaml";
    public static final String KAMELET_YAML_EXTENSION = ".kamelet.yaml";
    public static final String COMPOSE_FILENAME_PREFIX = "docker-compose.";
    public static final Map<String, String> DEFAULT_CONTAINER_RESOURCES = Map.of(
            "requests.memory", "256Mi",
            "requests.cpu", "500m",
            "limits.memory", "2048Mi",
            "limits.cpu", "2000m"
    );
    private static final String DOCKER_FOLDER = "/docker/";
    private static final String KUBERNETES_FOLDER = "/kubernetes/";
    private static final String BUILDER_POD_FRAGMENT_FILENAME = "builder.pod.jkube.yaml";
    private static final String BUILDER_COMPOSE_FILENAME = "builder.docker-compose.yaml";
    private static final String BUILDER_STACK_FILENAME = "builder.docker-stack.yaml";
    private final KaravanConfig config;
    private final ConfigService configService;
    private final KaravanCache karavanCache;
    private final GitService gitService;
    private final Vertx vertx;
    List<String> beansTemplates = List.of("database", "messaging");

    private static String applicationPropertiesTemplate(String runtime) {
        CamelRuntime r = CamelRuntime.fromValue(runtime);
        return switch (r) {
            case QUARKUS -> "application-quarkus.properties";
            case SPRING_BOOT -> "application-springboot.properties";
            default -> APPLICATION_PROPERTIES_FILENAME;
        };
    }

    public static String getProperty(String file, String property) {
        String prefix = property + "=";
        return Arrays.stream(file.split("\\r?\\n")) // handle both \n and \r\n line endings
                .filter(s -> s.startsWith(prefix))
                .findFirst()
                .map(s -> s.substring(prefix.length()).trim()) // remove prefix and trailing whitespace/\r
                .orElse("");
    }

    public static String getPropertyName(String line) {
        var parts = line.indexOf("=");
        return line.substring(0, parts).trim();
    }

    public static String getGavPackageSuffix(String projectId) {
        return projectId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    public static String replaceProperty(String file, String property, String value) {
        return file.lines().map(line -> {
            if (line.startsWith(property)) {
                return property + "=" + value;
            } else {
                return line;
            }
        }).collect(Collectors.joining(System.lineSeparator()));
    }

    public static String removePropertiesStartWith(String file, String startWith) {
        return file.lines().filter(line -> !line.startsWith(startWith))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String toEnvFormat(String input) {
        return input.replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
    }

    private static Set<String> findVariables(String template) {
        Set<String> variables = new HashSet<>();

        StringLookup dummyLookup = key -> {
            variables.add(key);
            return null; // Return null because we are only interested in collecting variable names
        };

        StringSubstitutor s = new StringSubstitutor(dummyLookup);
        s.replace(template);

        return variables;
    }

    public String getProjectDevModeImage(String projectId) {
        try {
            ProjectFile appProp = getApplicationProperties(projectId);
            return getPropertyValue(appProp.getCode(), DEVMODE_IMAGE);
        } catch (Exception e) {
            log.error("getProjectDevModeImage " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return null;
        }
    }

    private ProjectFile getApplicationProperties(String projectId) {
        return karavanCache.getProjectFile(projectId, APPLICATION_PROPERTIES_FILENAME);
    }

    public Map<String, String> getProjectFilesForDevMode(String projectId, Boolean withKamelets) {
        Map<String, String> files = karavanCache.getProjectFiles(projectId).stream()
                .filter(f -> !f.getName().endsWith(MARKDOWN_EXTENSION))
                .filter(f -> !Objects.equals(f.getName(), PROJECT_COMPOSE_FILENAME))
                .filter(f -> !f.getName().endsWith(PROJECT_JKUBE_EXTENSION))
                .filter(this::isDevFile)
                .collect(Collectors.toMap(ProjectFile::getName, ProjectFile::getCode));

        if (withKamelets) {
            karavanCache.getProjectFiles(ProjectFolder.Type.kamelets.name())
                    .forEach(file -> files.put(file.getName(), file.getCode()));
        }
        return files;
    }

    private boolean isDevFile(ProjectFile f) {
        var filename = f.getName();
        var parts = filename.split("\\.");
        var prefix = parts[0];
        return !configService.getEnvs().contains(prefix);
    }

    public String getBuilderPodFragment() {
        ProjectFile projectFile = karavanCache.getProjectFile(ProjectFolder.Type.configuration.name(), BUILDER_POD_FRAGMENT_FILENAME);
        return projectFile != null ? projectFile.getCode() : null;
    }

    public String getDeploymentFragment(String projectId) {
        ProjectFile projectFile = karavanCache.getProjectFile(projectId, PROJECT_DEPLOYMENT_JKUBE_FILENAME);
        return projectFile != null ? projectFile.getCode() : null;
    }

    public Map<String, String> getSshFiles() {
        Map<String, String> sshFiles = new HashMap<>(2);
        Tuple2<String, String> sshFileNames = gitService.getSShFiles();
        if (sshFileNames.getItem1() != null) {
            sshFiles.put("id_rsa", getFileString(sshFileNames.getItem1()));
        }
        if (sshFileNames.getItem2() != null) {
            sshFiles.put("known_hosts", getFileString(sshFileNames.getItem2()));
        }
        return sshFiles;
    }

    public String getBuilderComposeFragment(String projectId, String tag) {
        ProjectFile projectFile = karavanCache.getProjectFile(ProjectFolder.Type.configuration.name(), BUILDER_COMPOSE_FILENAME);
        var code = projectFile != null ? projectFile.getCode() : null;
        var code2 = substituteVariables(code, Map.of("projectId", projectId, "tag", tag));
        return replaceEnvWithRuntimeProperties(code2);
    }

    public String getBuilderStackFragment(String projectId, String tag) {
        ProjectFile projectFile = karavanCache.getProjectFile(ProjectFolder.Type.configuration.name(), BUILDER_STACK_FILENAME);
        var code = projectFile != null ? projectFile.getCode() : null;
        var code2 = substituteVariables(code, Map.of("projectId", projectId, "tag", tag));
        return replaceEnvWithRuntimeProperties(code2);
    }

    public String substituteVariables(String template, Map<String, String> variables) {
        StringSubstitutor sub = new StringSubstitutor(variables);
        return sub.replace(template);
    }

    public ProjectFile generateApplicationProperties(ProjectFolder projectFolder) {
        // Pick the application.properties template for the project's runtime. The output
        // file is always named application.properties; only the source template differs
        // (camel-main / quarkus / spring-boot). Falls back to the camel-main template if a
        // runtime-specific variant isn't bundled.
        String templateName = applicationPropertiesTemplate(projectFolder.getRuntime());
        String template = getTemplateText(templateName);
        if (template == null) {
            template = getTemplateText(APPLICATION_PROPERTIES_FILENAME);
        }
        String code = substituteVariables(template, Map.of(
                "projectId", projectFolder.getProjectId(),
                "projectName", projectFolder.getName(),
                "packageSuffix", CodeService.getGavPackageSuffix(projectFolder.getProjectId()),
                "camelVersion", config.camel().version(),
                "quarkusVersion", config.camel().quarkusVersion()
        ));
        return new ProjectFile(APPLICATION_PROPERTIES_FILENAME, code, projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
    }

    /**
     * The runtime a project is built/run with. Source of truth is the project's own
     * application.properties (camel.jbang.runtime) so imported projects are honoured too;
     * falls back to camel-main. Returned as the runtime value ("camel-main"/"quarkus"/
     * "spring-boot") and injected into the build as CAMEL_RUNTIME.
     */
    public String getProjectRuntime(String projectId) {
        ProjectFile appProps = karavanCache.getProjectFile(projectId, APPLICATION_PROPERTIES_FILENAME);
        if (appProps != null && appProps.getCode() != null) {
            String value = getPropertyValue(appProps.getCode(), PROPERTY_CAMEL_RUNTIME);
            if (value != null && !value.isBlank()) {
                return CamelRuntime.fromValue(value).getValue();
            }
        }
        return CamelRuntime.CAMEL_MAIN.getValue();
    }

    /**
     * A starter Camel route so a new project has runnable structure to edit ("Add Sample").
     */
    public ProjectFile createSampleIntegration(ProjectFolder projectFolder) {
        String code = "- route:\n" +
                "    id: sample\n" +
                "    from:\n" +
                "      uri: timer:sample\n" +
                "      parameters:\n" +
                "        period: \"5000\"\n" +
                "      steps:\n" +
                "        - log:\n" +
                "            message: \"Hello from " + projectFolder.getName() + "\"\n";
        return new ProjectFile("sample" + CAMEL_YAML_EXTENSION, code, projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
    }

    public String saveProjectFilesInTemp(Map<String, String> files) {
        String temp = vertx.fileSystem().createTempDirectoryBlocking("temp");
        files.forEach((fileName, code) -> addFile(temp, fileName, code));
        return temp;
    }

    private void addFile(String temp, String fileName, String code) {
        try {
            String path = temp + File.separator + fileName;
            vertx.fileSystem().writeFileBlocking(path, Buffer.buffer(code));
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public String getBuilderScript() {
        String envTemplate = getConfigurationText(config.environment() + "." + BUILD_SCRIPT_FILENAME);
        return envTemplate != null ? envTemplate : getConfigurationText(BUILD_SCRIPT_FILENAME);
    }

    /**
     * Re-seed a single bundled "configuration" build-scaffolding file (e.g. build.sh)
     * from the classpath over the persisted copy when it has drifted. addBuildInProject
     * only adds missing files, so without this an app-level build fix (such as the now
     * mandatory --runtime export flag) would never reach an existing install.
     */
    public void refreshConfigurationFile(String fileName) {
        try {
            String bundled = getBuildInProjectFiles(ProjectFolder.Type.configuration.name()).get(fileName);
            if (bundled == null) {
                return;
            }
            ProjectFile stored = karavanCache.getProjectFile(ProjectFolder.Type.configuration.name(), fileName);
            String storedCode = stored != null ? stored.getCode().replaceAll("\r\n", "\n") : null;
            if (!bundled.replaceAll("\r\n", "\n").equals(storedCode)) {
                ProjectFile file = new ProjectFile(fileName, bundled, ProjectFolder.Type.configuration.name(),
                        Instant.now().getEpochSecond() * 1000L);
                karavanCache.saveProjectFile(file, null, false);
                log.info("Refreshed bundled configuration file: " + fileName);
            }
        } catch (Exception e) {
            log.error("Error refreshing configuration file " + fileName, e);
        }
    }

    public String getConfigurationText(String fileName) {
        try {
            List<ProjectFile> files = karavanCache.getProjectFiles(ProjectFolder.Type.configuration.name());
            // replaceAll("\r\n", "\n")) has been add to eliminate the impact of editing the template files from windows machine.
            return files.stream().filter(f -> f.getName().equalsIgnoreCase(fileName))
                    .map(file -> file.getCode().replaceAll("\r\n", "\n")).findFirst().orElse(null);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return null;
    }

    public String getTemplateText(String fileName) {
        try {
            List<ProjectFile> files = karavanCache.getProjectFiles(ProjectFolder.Type.templates.name());
            // replaceAll("\r\n", "\n")) has been add to eliminate the impact of editing the template files from windows machine.
            return files.stream().filter(f -> f.getName().equalsIgnoreCase(fileName))
                    .map(file -> file.getCode().replaceAll("\r\n", "\n")).findFirst().orElse(null);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return null;
    }

    public Map<String, String> getBuildInProjectFiles(String projectId) {
        Map<String, String> result = new HashMap<>();

        var path = "/" + projectId + (ConfigService.inKubernetes() ? KUBERNETES_FOLDER : DOCKER_FOLDER);

        listResources(path).forEach(filename -> {
            if (!filename.startsWith(".")) {
                String templatePath = path + filename;
                String templateText = getResourceFile(templatePath);
                if (templateText != null) {
                    result.put(filename, templateText);
                }
            }
        });
        return result;
    }

    public List<String> getBuildInProjectFileList(String projectId) {
        var path = "/" + projectId + (ConfigService.inKubernetes() ? KUBERNETES_FOLDER : DOCKER_FOLDER);
        return listResources(path);
    }

    public String getResourceFile(String path) {
        try (InputStream inputStream = CodeService.class.getResourceAsStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (Exception e) {
            return null;
        }
    }

    public String getPropertyValue(String propFileText, String key) {
        Optional<String> data = propFileText.lines().filter(p -> p.startsWith(key)).findFirst();
        return data.map(s -> s.split("=")[1]).orElse(null);
    }

    public String getPropertyValueByKeyContains(String propFileText, String key) {
        Optional<String> data = propFileText.lines().filter(p -> {
            var split = p.split("=");
            var first = split[0];
            return first.contains(key);
        }).findFirst();
        return data.map(s -> s.split("=")[1]).orElse(null);
    }

    private ObjectNode readNodeFromJson(String openApi) throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        return (ObjectNode) mapper.readTree(openApi);
    }

    private ObjectNode readNodeFromYaml(String yaml) throws FileNotFoundException {
        final ObjectMapper mapper = new ObjectMapper();
        Yaml loader = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map map = loader.load(yaml);
        return mapper.convertValue(map, ObjectNode.class);
    }

    public String getPropertiesFile(List<PathCommitDetails> folderFiles) {
        try {
            for (PathCommitDetails e : folderFiles) {
                if (e.fileName().equalsIgnoreCase(APPLICATION_PROPERTIES_FILENAME)) {
                    return e.content();
                }
            }
        } catch (Exception e) {

        }
        return null;
    }

    public String getProjectProperty(String projectId, String property) {
        var file = getApplicationProperties(projectId);
        if (file != null) {
            return getProperty(file.getCode(), property);
        } else {
            return null;
        }
    }

    public String getProjectName(String file) {
        String name = getProperty(file, PROPERTY_CAMEL_MAIN_DESCRIPTION);
        if (name.isBlank()) name = getProperty(file, PROPERTY_PROJECT_NAME);
        return !name.isBlank() ? name : getProperty(file, PROPERTY_PROJECT_NAME_OLD);
    }

    public String getProjectId(String file) {
        String name = getProperty(file, PROPERTY_CAMEL_MAIN_NAME);
        return !name.isBlank() ? name : getProperty(file, PROPERTY_PROJECT_ID);
    }

    public ProjectFile createInitialProjectCompose(ProjectFolder projectFolder, int nextAvailablePort) {
        String template = getTemplateText(PROJECT_COMPOSE_FILENAME);
        String code = substituteVariables(template, Map.of(
                "projectId", projectFolder.getProjectId(),
                "projectPort", String.valueOf(nextAvailablePort),
                "projectImage", projectFolder.getProjectId()
        ));
        return new ProjectFile(PROJECT_COMPOSE_FILENAME, code, projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
    }

    public ProjectFile createInitialProjectStack(ProjectFolder projectFolder, int nextAvailablePort) {
        String template = getTemplateText(PROJECT_STACK_FILENAME);
        String code = substituteVariables(template, Map.of(
                "projectId", projectFolder.getProjectId(),
                "projectPort", String.valueOf(nextAvailablePort),
                "projectImage", projectFolder.getProjectId()
        ));
        return new ProjectFile(PROJECT_STACK_FILENAME, code, projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
    }

    public ProjectFile createInitialDeployment(ProjectFolder projectFolder) {
        String template = getTemplateText(PROJECT_DEPLOYMENT_JKUBE_FILENAME);
        return new ProjectFile(PROJECT_DEPLOYMENT_JKUBE_FILENAME, template, projectFolder.getProjectId(), Instant.now().getEpochSecond() * 1000L);
    }

    public Integer getProjectPort(ProjectFile composeFile) {
        if (composeFile != null) {
            DockerComposeService dcs = DockerComposeConverter.fromCode(composeFile.getCode(), composeFile.getProjectId());
            Optional<Integer> port = dcs.getPortsMap().entrySet().stream()
                    .filter(e -> Objects.equals(e.getValue(), INTERNAL_PORT)).map(Map.Entry::getKey).findFirst();
            return port.orElse(null);
        }
        return null;
    }

    public Integer getProjectPort(String projectId) {
        ProjectFile composeFile = karavanCache.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME);
        return getProjectPort(composeFile);
    }

    public String getDockerComposeFileForProject(String projectId) {
        String composeFileName = PROJECT_COMPOSE_FILENAME;
        if (!Objects.equals(config.environment(), DEV)) {
            composeFileName = config.environment() + "." + PROJECT_COMPOSE_FILENAME;
        }
        ProjectFile compose = karavanCache.getProjectFile(projectId, composeFileName);
        if (compose != null) {
            return compose.getCode();
        }
        return null;
    }

    public String getDockerStackFileForProject(String projectId) {
        String stackFileName = PROJECT_STACK_FILENAME;
        if (!Objects.equals(config.environment(), DEV)) {
            stackFileName = config.environment() + "." + PROJECT_STACK_FILENAME;
        }
        ProjectFile stack = karavanCache.getProjectFile(projectId, stackFileName);
        if (stack != null) {
            return stack.getCode();
        }
        return null;
    }

    public void updateDockerComposeImage(String projectId, String imageName) {
        ProjectFile compose = karavanCache.getProjectFile(projectId, PROJECT_COMPOSE_FILENAME);
        if (compose != null) {
            DockerComposeService service = DockerComposeConverter.fromCode(compose.getCode(), projectId);
            service.setImage(imageName);
            String code = DockerComposeConverter.toCode(service);
            compose.setCode(code);
            compose.setLastUpdate(Instant.now().getEpochSecond() * 1000L);
            karavanCache.saveProjectFile(compose, null, true);
        }
    }

    public String replaceEnvWithRuntimeProperties(String code) {
        Map<String, String> env = new HashMap<>();
        findVariables(code).forEach(envName -> {
            String envValue = getConfigValue(envName);
            env.put(envName, envValue);
        });
        return substituteVariables(code, env);
    }

    private String getConfigValue(String envName) {
        ConfigValue val = ConfigProvider.getConfig().getConfigValue(envName);
        if (val == null) {
            var canonicalName = toEnvFormat(envName);
            val = ConfigProvider.getConfig().getConfigValue(canonicalName);
        }
        return val != null ? val.getValue() : System.getProperty(envName, null);
    }

    private List<String> getEnvironmentVariablesFromString(String file) {
        List<String> vars = new ArrayList<>();
        if (file != null) {
            vars = file.lines().collect(Collectors.toList());
        }
        return vars;
    }

    public List<String> listResources(String resourceFolder) {
        List<String> result = new ArrayList<>();
        try {
            if (CodeService.class.getResource(resourceFolder) != null) {
                URI uri = CodeService.class.getResource(resourceFolder).toURI();
                Path myPath;
                FileSystem fileSystem = null;
                if (uri.getScheme().equals("jar")) {
                    fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                    myPath = fileSystem.getPath(resourceFolder);
                } else {
                    myPath = Paths.get(uri);
                }

                try (var pathsStream = Files.walk(myPath, 10)) {
                    pathsStream
                            .filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .forEach(result::add);
                } catch (IOException e) {
                    var error = e.getCause() != null ? e.getCause() : e;
                    log.error("IOException", error);
                }
                if (fileSystem != null) {
                    fileSystem.close();
                }
            }
        } catch (URISyntaxException | IOException e) {
            var error = e.getCause() != null ? e.getCause() : e;
            log.error("URISyntaxException | IOException", error);
        }
        return result;
    }

    public String getFileString(String fullName) {
        return vertx.fileSystem().readFileBlocking(fullName).toString();
    }

    public String getGavFormatter() {
        return PROPERTY_NAME_GAV + "=" + getGav();
    }

    public String getGav() {
        return config.gav().orElse("org.camel.karavan.demo") + ":%s:1";
    }
}
