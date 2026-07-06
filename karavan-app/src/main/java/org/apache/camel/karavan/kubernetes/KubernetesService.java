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
package org.apache.camel.karavan.kubernetes;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.mutiny.tuples.Tuple2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.karavan.config.KaravanConfig;
import org.apache.camel.karavan.model.*;
import org.apache.camel.karavan.service.CodeService;
import org.apache.camel.karavan.service.ConfigService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.apache.camel.karavan.KaravanConstants.*;
import static org.apache.camel.karavan.service.CodeService.BUILD_SCRIPT_FILENAME;

@Default
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class KubernetesService {

    private final KaravanConfig config;

    private final CodeService codeService;

    private String namespace;

    @Produces
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    public void createConfigmap(String name, Map<String, String> data) {
        log.info("Creating configmap " + name);
        if (ConfigService.inKubernetes()) {
            try (KubernetesClient client = kubernetesClient()) {
                ConfigMap configMap = client.configMaps().inNamespace(getNamespace()).withName(name).get();
                if (configMap == null) {
                    configMap = new ConfigMapBuilder()
                            .withMetadata(new ObjectMetaBuilder()
                                    .withName(name)
                                    .withLabels(getPartOfLabels())
                                    .withNamespace(getNamespace())
                                    .build())
                            .build();
                    configMap.setData(data);
                    client.resource(configMap).create();
                } else {
                    configMap.setData(data);
                    client.resource(configMap).update();
                }

            } catch (Exception e) {
                log.error("Error create Configmap: " + e.getMessage());
            }
        }
    }

    public void runBuildProject(String projectId, String podFragment, Map<String, String> envVars) {
        try (KubernetesClient client = kubernetesClient()) {
            String containerName = projectId + BUILDER_SUFFIX;
            Map<String, String> labels = getLabels(containerName, projectId, ContainerType.build);

//        Delete old build pod
            Pod old = client.pods().inNamespace(getNamespace()).withName(containerName).get();
            if (old != null) {
                client.resource(old).delete();
            }
            boolean hasDockerConfigSecret = hasDockerConfigSecret();
            Pod pod = getBuilderPod(containerName, labels, podFragment, hasDockerConfigSecret, envVars);
            Pod result = client.resource(pod).create();

            log.info("Created pod " + result.getMetadata().getName());
        } catch (Exception e) {
            log.error("Error creating build container: " + e.getMessage());
        }
    }

    private Map<String, String> getLabels(String name, String projectId, ContainerType type) {
        Map<String, String> labels = new HashMap<>();
        labels.putAll(getPartOfLabels());
        labels.put("app.kubernetes.io/name", name);
        labels.put(LABEL_PROJECT_ID, projectId);
        if (type != null) {
            labels.put(LABEL_TYPE, type.name());
        }
        if (Objects.equals(type, ContainerType.devmode)) {
            labels.put(LABEL_CAMEL_RUNTIME, CamelRuntime.CAMEL_MAIN.getValue());
            labels.putAll(getRuntimeLabels());
        }
        return labels;
    }

    private Map<String, String> getRuntimeLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put(isOpenshift() ? "app.openshift.io/runtime" : LABEL_KUBERNETES_RUNTIME, CAMEL_PREFIX);
        return labels;
    }

    public Map<String, String> getPartOfLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put(LABEL_PART_OF, ConfigService.getAppName());
        return labels;
    }

    // TODO: Move all possible stuff to pod fragment
    private Pod getBuilderPod(String name, Map<String, String> labels, String configFragment, boolean hasDockerConfigSecret, Map<String, String> envVars) {
        ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withLabels(labels)
                .withNamespace(getNamespace())
                .build();

        ContainerPort port = new ContainerPortBuilder()
                .withContainerPort(8080)
                .withName("http")
                .withProtocol("TCP")
                .build();

        List<VolumeMount> volumeMounts = new ArrayList<>();
        volumeMounts.add(new VolumeMountBuilder().withName(BUILD_SCRIPT_VOLUME_NAME).withMountPath("/karavan/builder").withReadOnly(true).build());
        if (hasDockerConfigSecret) {
            volumeMounts.add(new VolumeMountBuilder().withName(BUILD_DOCKER_CONFIG_SECRET).withMountPath("/karavan/.docker").withReadOnly(true).build());
        }
        if (config.privateKeyPath().isPresent()) {
            volumeMounts.add(new VolumeMountBuilder().withName(PRIVATE_KEY_SECRET_KEY).withMountPath("/karavan/.ssh/id_rsa").withSubPath("id_rsa").withReadOnly(true).build());
            volumeMounts.add(new VolumeMountBuilder().withName(KNOWN_HOSTS_SECRET_KEY).withMountPath("/karavan/.ssh/known_hosts").withSubPath("known_hosts").withReadOnly(true).build());
        }

        Pod pod = Serialization.unmarshal(configFragment, Pod.class);

        pod.getSpec().getContainers().getFirst().getEnv().add(new EnvVarBuilder().withName(ENV_VAR_RUN_IN_BUILD_MODE).withValue("true").build());
        envVars.forEach((key, value) -> pod.getSpec().getContainers().getFirst().getEnv().add(new EnvVarBuilder().withName(key).withValue(value).build()));

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(config.devmode().image())
                .withPorts(port)
                .withImagePullPolicy(config.devmode().imagePullPolicy().orElse("IfNotPresent"))
                .withEnv(pod.getSpec().getContainers().getFirst().getEnv())
                .withCommand("/bin/sh", "-c", "/karavan/builder/build.sh")
                .withVolumeMounts(volumeMounts)
                .build();

        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName(BUILD_SCRIPT_VOLUME_NAME)
                .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(BUILD_SCRIPT_CONFIG_MAP).withItems(
                        new KeyToPathBuilder().withKey(BUILD_SCRIPT_FILENAME).withPath(BUILD_SCRIPT_FILENAME).build()
                ).withDefaultMode(511).build()).build());
        if (hasDockerConfigSecret) {
            volumes.add(new VolumeBuilder().withName(BUILD_DOCKER_CONFIG_SECRET)
                    .withSecret(new SecretVolumeSourceBuilder().withSecretName(BUILD_DOCKER_CONFIG_SECRET).withItems(
                            new KeyToPathBuilder().withKey(".dockerconfigjson").withPath("config.json").build()
                    ).withDefaultMode(511).build()).build());
        }
        if (config.privateKeyPath().isPresent()) {
            volumes.add(new VolumeBuilder().withName(PRIVATE_KEY_SECRET_KEY)
                    .withSecret(new SecretVolumeSourceBuilder().withSecretName(config.secret().name()).withItems(
                            new KeyToPathBuilder().withKey(PRIVATE_KEY_SECRET_KEY).withPath("id_rsa").build()
                    ).withDefaultMode(511).build()).build());
            volumes.add(new VolumeBuilder().withName(KNOWN_HOSTS_SECRET_KEY)
                    .withSecret(new SecretVolumeSourceBuilder().withSecretName(config.secret().name()).withItems(
                            new KeyToPathBuilder().withKey(KNOWN_HOSTS_SECRET_KEY).withPath("known_hosts").build()
                    ).withDefaultMode(511).build()).build());
        }

        PodSpec spec = new PodSpecBuilder()
                .withTerminationGracePeriodSeconds(0L)
                .withContainers(container)
                .withRestartPolicy("Never")
                .withServiceAccount(config.builder().service().account())
                .withVolumes(volumes)
                .build();

        return new PodBuilder()
                .withMetadata(meta)
                .withSpec(spec)
                .build();
    }

    public boolean hasDockerConfigSecret() {
        try (KubernetesClient client = kubernetesClient()) {
            return client.secrets().inNamespace(getNamespace()).withName(BUILD_DOCKER_CONFIG_SECRET).get() != null;
        } catch (Exception ex) {
            log.error(ex.getMessage());
            return false;
        }
    }

    public Tuple2<LogWatch, KubernetesClient> getContainerLogWatch(String podName) {
        KubernetesClient client = kubernetesClient();
        // watchLog() on a missing pod throws a KubernetesClientException whose
        // message is the raw k8s Status JSON ({"kind":"Status",...,"code":404})
        // — check existence first and fail with a clean, log-friendly message
        // (the SSE stream then just closes; the client retries with backoff).
        if (client.pods().inNamespace(getNamespace()).withName(podName).get() == null) {
            client.close();
            throw new IllegalStateException("Pod " + podName + " not found - no log to watch");
        }
        LogWatch logWatch = client.pods().inNamespace(getNamespace()).withName(podName).tailingLines(100).watchLog();
        return Tuple2.of(logWatch, client);
    }

    public void rolloutDeployment(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            client.apps().deployments().inNamespace(getNamespace()).withName(name).rolling().restart();
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void startDeployment(String resources, Map<String, String> labels) {
        try (KubernetesClient client = kubernetesClient()) {
            KubernetesList list = Serialization.unmarshal(resources, KubernetesList.class);
            list.getItems().forEach(item -> {
                if (labels != null) {
                    item.getMetadata().getLabels().putAll(labels);
                    if (item instanceof Deployment deployment) {
                        deployment.getSpec().getTemplate().getMetadata().getLabels().putAll(labels);
                    }
                }
                client.resource(item).inNamespace(getNamespace()).serverSideApply();
            });
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void deleteDeployment(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            log.info("Delete deployment: " + name + " in the namespace: " + getNamespace());
            client.apps().deployments().inNamespace(getNamespace()).withName(name).delete();
            client.services().inNamespace(getNamespace()).withName(name).delete();
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public void deletePod(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            Pod pod = client.pods().inNamespace(getNamespace()).withName(name).get();
            String deploymentName = ownerDeploymentName(client, pod);
            if (deploymentName != null) {
                // The pod is managed by a Deployment — deleting just the pod makes the
                // Deployment immediately recreate it ("creates another one"). "Delete"
                // on a deployment pod means undeploy: remove the Deployment + Service.
                log.info("Pod " + name + " is managed by deployment " + deploymentName + "; deleting the deployment");
                deleteDeployment(deploymentName);
                return;
            }
            log.info("Delete pod: " + name);
            client.pods().inNamespace(getNamespace()).withName(name).delete();
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    /**
     * Resolves the Deployment that owns a pod (Pod -> ReplicaSet -> Deployment), or
     * null for a standalone pod (e.g. the one-off build pod). Used so that deleting a
     * deployment-managed pod undeploys the Deployment instead of being recreated.
     */
    private String ownerDeploymentName(KubernetesClient client, Pod pod) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getOwnerReferences() == null) {
            return null;
        }
        for (OwnerReference ref : pod.getMetadata().getOwnerReferences()) {
            if ("Deployment".equals(ref.getKind())) {
                return ref.getName();
            }
            if ("ReplicaSet".equals(ref.getKind())) {
                ReplicaSet rs = client.apps().replicaSets().inNamespace(getNamespace()).withName(ref.getName()).get();
                if (rs != null && rs.getMetadata().getOwnerReferences() != null) {
                    for (OwnerReference rsRef : rs.getMetadata().getOwnerReferences()) {
                        if ("Deployment".equals(rsRef.getKind())) {
                            return rsRef.getName();
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<String> getConfigMaps(String namespace) {
        List<String> result = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.configMaps().inNamespace(namespace).list().getItems().forEach(configMap -> {
                String name = configMap.getMetadata().getName();
                if (configMap.getData() != null) {
                    configMap.getData().keySet().forEach(data -> result.add(name + "/" + data));
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public List<String> getSecrets(String namespace) {
        List<String> result = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.secrets().inNamespace(namespace).list().getItems().forEach(secret -> {
                String name = secret.getMetadata().getName();
                if (secret.getData() != null) {
                    secret.getData().keySet().forEach(data -> result.add(name + "/" + data));
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public List<String> getServices(String namespace) {
        List<String> result = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.services().inNamespace(namespace).list().getItems().forEach(service -> {
                String name = service.getMetadata().getName();
                String host = name + "." + namespace + ".svc.cluster.local";
                service.getSpec().getPorts().forEach(port -> result.add(name + "|" + host + ":" + port.getPort()));
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public void runDevModeContainer(String projectId, Boolean verbose, Boolean compile, Map<String, String> files, String projectDevmodeImage, String deploymentFragment, Map<String, String> labels, Map<String, String> envVars, String runtime, String quarkusVersion) {
        Map<String, String> podLabels = new HashMap<>(labels);
        podLabels.putAll(getLabels(projectId, projectId, ContainerType.devmode));

        try (KubernetesClient client = kubernetesClient()) {
            if (config.devmode().createm2().orElse(false)) {
                createPVC(projectId, labels);
            }
            Pod old = client.pods().inNamespace(getNamespace()).withName(projectId).get();
            if (old == null) {
                Pod pod = getDevModePod(projectId, verbose, compile, podLabels, projectDevmodeImage, deploymentFragment, envVars, runtime, quarkusVersion);
                Pod result = client.resource(pod).serverSideApply(); // important
                result = client.pods().inNamespace(getNamespace()).withName(projectId).waitUntilReady(30, TimeUnit.SECONDS);
                log.info("Pod " + result.getMetadata().getName() + " status " + result.getStatus());
                var copyFiles = copyFilesToContainer(result, files, "/karavan/code");
                log.info("Pod files copy result is " + copyFiles);
                var copyDone = copyFilesToContainer(result, Map.of(".karavan.done", "done"), "/tmp");
                log.info("Pod files copy done is " + copyDone);
                log.info("Pod pod " + result.getMetadata().getName());
            }
        }
        createService(projectId, podLabels);
    }

    private boolean copyFilesToContainer(Pod pod, Map<String, String> files, String dirName) {
        try (KubernetesClient client = kubernetesClient()) {
            String temp = codeService.saveProjectFilesInTemp(files);
            return client.pods().inNamespace(getNamespace())
                    .withName(pod.getMetadata().getName())
                    .dir(dirName)
                    .upload(Paths.get(temp));
        } catch (Exception e) {
            log.info("Error copying filed to devmode pod: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return false;
        }
    }

    public void deletePodAndService(String name, boolean deletePVC) {
        try (KubernetesClient client = kubernetesClient()) {
            log.info("Delete pod/service: " + name + " in the namespace: " + getNamespace());
            client.pods().inNamespace(getNamespace()).withName(name).delete();
            client.services().inNamespace(getNamespace()).withName(name).delete();
            if (deletePVC) {
                client.persistentVolumeClaims().inNamespace(getNamespace()).withName(name).delete();
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    public ResourceRequirements getResourceRequirements(Map<String, String> containerResources) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(containerResources.get("requests.cpu")))
                .addToRequests("memory", new Quantity(containerResources.get("requests.memory")))
                .addToLimits("cpu", new Quantity(containerResources.get("limits.cpu")))
                .addToLimits("memory", new Quantity(containerResources.get("limits.memory")))
                .build();
    }

    private Pod getDevModePod(String name, Boolean verbose, Boolean compile, Map<String, String> labels, String projectDevmodeImage, String deploymentFragment, Map<String, String> envVars, String runtime, String quarkusVersion) {

        // Projects imported from external repos may lack deployment.jkube.yaml -
        // devmode must still start (unmarshal(null) NPEs inside fabric8).
        PodSpec podSpec = new PodSpec();
        if (deploymentFragment != null && !deploymentFragment.isBlank()) {
            try {
                Deployment deployment = Serialization.unmarshal(deploymentFragment, Deployment.class);
                podSpec = deployment.getSpec().getTemplate().getSpec();
            } catch (Exception ignored) {
                podSpec = new PodSpec();
            }
        }
        List<VolumeMount> volumeMounts = new ArrayList<>();
        try {
            volumeMounts = podSpec.getContainers().getFirst().getVolumeMounts();
        } catch (Exception ignored) {
        }

        Map<String, String> containerResources = CodeService.DEFAULT_CONTAINER_RESOURCES;
        ResourceRequirements resources = getResourceRequirements(containerResources);

        ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withLabels(labels)
                .withNamespace(getNamespace())
                .build();

        ContainerPort port = new ContainerPortBuilder()
                .withContainerPort(8080)
                .withName("http")
                .withProtocol("TCP")
                .build();

        List<EnvVar> environmentVariables = new ArrayList<>();
        try {
            environmentVariables = new ArrayList<>(podSpec.getContainers().getFirst().getEnv());
        } catch (Exception ignored) {
        }

        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            environmentVariables.add(new EnvVarBuilder().withName(k).withValue(v).build());
        }
        if (verbose) {
            environmentVariables.add(new EnvVarBuilder().withName(ENV_VAR_VERBOSE_OPTION_NAME).withValue(ENV_VAR_VERBOSE_OPTION_VALUE).build());
        }
        if (compile) {
            environmentVariables.add(new EnvVarBuilder().withName(ENV_VAR_RUN_IN_COMPILE_MODE).withValue("true").build());
        }

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName(name)
                .withImage(projectDevmodeImage != null ? projectDevmodeImage : config.devmode().image())
                .withPorts(port)
                .withResources(resources)
                .withImagePullPolicy(config.devmode().imagePullPolicy().orElse("IfNotPresent"))
                .withEnv(environmentVariables)
                .withVolumeMounts(volumeMounts);
        // camel-main dev mode uses the image's default CMD (`camel run`, JBang, hot-reload).
        // quarkus/spring-boot need an explicit `camel run --runtime=...` (mvn-based dev). Two
        // tweaks make it work in the existing image: (1) wait for the /tmp/.karavan.done marker
        // the orchestrator drops AFTER copying the project files, so the mvn-based runtimes start
        // with the sources present (they don't hot-pick an initially-empty dir the way JBang does);
        // (2) unset MAVEN_CONFIG (the image sets it to the repo dir, which Maven otherwise mis-reads
        // as a lifecycle phase -> "Unknown lifecycle phase /karavan/.m2").
        if (runtime != null && !CamelRuntime.CAMEL_MAIN.getValue().equals(runtime)) {
            String quarkusVersionArg = (CamelRuntime.QUARKUS.getValue().equals(runtime) && quarkusVersion != null && !quarkusVersion.isBlank())
                    ? " --quarkus-version=" + quarkusVersion : "";
            String devCommand = "i=0; while [ ! -f /tmp/.karavan.done ] && [ $i -lt 120 ]; do sleep 0.5; i=$((i+1)); done; "
                    + "unset MAVEN_CONFIG; "
                    + "exec jbang -Dcamel.jbang.version=$CAMEL_VERSION camel@apache/camel run --source-dir=/karavan/code --runtime=" + runtime
                    + quarkusVersionArg + " --console" + (verbose ? " --verbose" : "");
            containerBuilder.withCommand("sh", "-c", devCommand);
        }
        Container container = containerBuilder.build();

        podSpec.setTerminationGracePeriodSeconds(0L);
        podSpec.setContainers(List.of(container));
        podSpec.setRestartPolicy("Never");
        podSpec.setServiceAccount(config.devmode().service().account());
        if (config.devmode().createm2().orElse(false)) {
            podSpec.getVolumes().add(new VolumeBuilder().withName(name).withNewPersistentVolumeClaim(name, false).build());
        }

        return new PodBuilder()
                .withMetadata(meta)
                .withSpec(podSpec)
                .build();
    }

    private void createPVC(String podName, Map<String, String> labels) {
        try (KubernetesClient client = kubernetesClient()) {
            PersistentVolumeClaim old = client.persistentVolumeClaims().inNamespace(getNamespace()).withName(podName).get();
            if (old == null) {
                PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                        .withNewMetadata()
                        .withName(podName)
                        .withNamespace(getNamespace())
                        .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                        .withResources(new VolumeResourceRequirementsBuilder().withRequests(Map.of("storage", new Quantity("2Gi"))).build())
                        .withVolumeMode("Filesystem")
                        .withAccessModes("ReadWriteOnce")
                        .endSpec()
                        .build();
                client.resource(pvc).serverSideApply();
            }
        }
    }

    private void createService(String name, Map<String, String> labels) {
        try (KubernetesClient client = kubernetesClient()) {
            ServicePort http = new ServicePortBuilder()
                    .withName("http").withPort(80).withProtocol("TCP").withTargetPort(new IntOrString(8080)).build();
            ServicePort https = new ServicePortBuilder()
                    .withName("https").withPort(443).withProtocol("TCP").withTargetPort(new IntOrString(8080)).build();

            Service service = new ServiceBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(getNamespace())
                    .withLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                    .withType("ClusterIP")
                    .withPorts(http, https)
                    .withSelector(labels)
                    .endSpec()
                    .build();
            client.resource(service).serverSideApply();
        }
    }

    public void createSecret(String name, Map<String, String> data, Map<String, String> labels) {
        try (KubernetesClient client = kubernetesClient()) {
            Secret secret = new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(getNamespace())
                    .withLabels(labels)
                    .endMetadata()
                    .withStringData(data)
                    .build();
            client.resource(secret).serverSideApply();
        }
    }

    public void createConfigMap(String name, Map<String, String> data, Map<String, String> labels) {
        try (KubernetesClient client = kubernetesClient()) {
            ConfigMap configMap = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(getNamespace())
                    .withLabels(labels)
                    .endMetadata()
                    .withData(data)
                    .build();
            client.resource(configMap).serverSideApply();
        }
    }

    public Secret getSecret(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            return client.secrets().inNamespace(getNamespace()).withName(name).get();
        }
    }

    public Secret getKaravanSecret() {
        try (KubernetesClient client = kubernetesClient()) {
            return client.secrets().inNamespace(getNamespace()).withName(config.secret().name()).get();
        }
    }

    public String getKaravanSecret(String key) {
        try (KubernetesClient client = kubernetesClient()) {
            Secret secret = client.secrets().inNamespace(getNamespace()).withName(config.secret().name()).get();
            Map<String, String> data = secret.getData();
            return decodeSecret(data.get(key));
        }
    }

    public String getSecret(String name, String key) {
        try (KubernetesClient client = kubernetesClient()) {
            Secret secret = client.secrets().inNamespace(getNamespace()).withName(name).get();
            Map<String, String> data = secret.getData();
            return decodeSecret(data.get(key));
        }
    }

    private String decodeSecret(String data) {
        if (data != null) {
            return new String(Base64.getDecoder().decode(data.getBytes(StandardCharsets.UTF_8)));
        }
        return null;
    }

    public ConfigMap getConfigMap(String name) {
        try (KubernetesClient client = kubernetesClient()) {
            return client.configMaps().inNamespace(getNamespace()).withName(name).get();
        }
    }

    public boolean isOpenshift() {
        return config.openshift().orElse(false);
    }

    public String getNamespace() {
        if (namespace == null) {
            try (KubernetesClient client = kubernetesClient()) {
                namespace = LaunchMode.current().getProfileKey().equalsIgnoreCase("dev") ? "karavan" : client.getNamespace();
            }
        }
        return namespace;
    }

    public void updateSecret(Secret secret) {
        try (KubernetesClient client = kubernetesClient()) {
            client.resource(secret).update();
        }
    }

    public void updateConfigMap(ConfigMap configMap) {
        try (KubernetesClient client = kubernetesClient()) {
            client.resource(configMap).update();
        }
    }

    public String getSecretValue(String secretName, String secretKey) {
        return getSecret(secretName).getData().get(secretKey);
    }

    public void setSecretValue(String secretName, String secretKey, String value) {
        Secret secret = getSecret(secretName);
        if (secret != null) {
            secret.getData().put(secretKey, value);
            updateSecret(secret);
        }
    }

    public void createSecret(String secretName) {
        Secret secret = getSecret(secretName);
        if (secret == null) {
            createSecret(secretName, Map.of(), Map.of());
        }
    }

    public void deleteSecretValue(String secretName, String secretKey) {
        Secret secret = getSecret(secretName);
        if (secret != null) {
            secret.getData().remove(secretKey);
            updateSecret(secret);
        }
    }

    public List<KubernetesSecret> getSecrets() {
        List<KubernetesSecret> result = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.secrets().inNamespace(getNamespace()).list().getItems().forEach(secret -> {
                Map<String, String> data = new HashMap<>(secret.getData());
                data.replaceAll((s, s2) -> "");
                result.add(new KubernetesSecret(secret.getMetadata().getName(), data));
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public void deleteSecret(String secretName) {
        Secret secret = getSecret(secretName);
        if (secret != null) {
            try (KubernetesClient client = kubernetesClient()) {
                client.secrets().inNamespace(getNamespace()).withName(secretName).delete();
            }
        }
    }

    public List<KubernetesConfigMap> getConfigMaps() {
        List<KubernetesConfigMap> result = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.configMaps().inNamespace(getNamespace()).list().getItems()
                    .forEach(secret -> result.add(new KubernetesConfigMap(secret.getMetadata().getName(), new HashMap<>(secret.getData()))));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    public void deleteConfigMap(String configMapName) {
        ConfigMap configMap = getConfigMap(configMapName);
        if (configMap != null) {
            try (KubernetesClient client = kubernetesClient()) {
                client.configMaps().inNamespace(getNamespace()).withName(configMapName).delete();
            }
        }
    }

    public void setConfigMapValue(String configMapName, String configMapKey, String value) {
        ConfigMap configMap = getConfigMap(configMapName);
        if (configMap != null) {
            configMap.getData().put(configMapKey, value);
            updateConfigMap(configMap);
        }
    }

    public void createConfigMap(String configMapName) {
        ConfigMap configMap = getConfigMap(configMapName);
        if (configMap == null) {
            createConfigMap(configMapName, Map.of(), Map.of());
        }
    }

    public void deleteConfigMapValue(String configMapName, String configMapKey) {
        ConfigMap configMap = getConfigMap(configMapName);
        if (configMap != null) {
            configMap.getData().remove(configMapKey);
            updateConfigMap(configMap);
        }
    }

    public String getCluster() {
        try (KubernetesClient client = kubernetesClient()) {
            return client.getMasterUrl().getHost();
        }
    }

    public String getEnvironment() {
        return config.environment();
    }

    public List<PodEvent> getPodEvents(String containerName) {
        List<PodEvent> list = new ArrayList<>();
        try (KubernetesClient client = kubernetesClient()) {
            client.events().v1().events().inNamespace(getNamespace())
                    .withField("regarding.kind", "Pod")
                    .withField("regarding.name", containerName)
                    .list(new ListOptionsBuilder().withLimit(100L).build())
                    .getItems().forEach(e -> {
                        PodEvent pe = new PodEvent(
                                e.getMetadata().getName(),
                                e.getRegarding().getName(),
                                e.getReason(),
                                e.getNote(),
                                e.getMetadata().getCreationTimestamp());
                        list.add(pe);
                    });
        } catch (Exception e) {
            log.error("Error getting Pod Events" + e.getMessage());
        }
        return list;
    }
}
