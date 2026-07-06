#!/usr/bin/env bash
# Fail fast: stop at the first failing step (git clone / export / build / apply)
# so the build log shows the real cause instead of a cascade of follow-on errors.
set -eo pipefail

export NAMESPACE=$(cat /var/run/secrets/kubernetes.io/serviceaccount/namespace)

# Camel runtime to build, injected per-project from its application.properties
# (camel.jbang.runtime) by ProjectService. camel-main and spring-boot share the
# jib-exploded + jkube path; quarkus uses the Quarkus container-image + kubernetes
# extensions. Defaults to camel-main.
CAMEL_RUNTIME=${CAMEL_RUNTIME:-camel-main}

git config --global credential.helper 'cache --timeout=3600'
git_credential_fill() {
    echo url=$GIT_REPOSITORY
    echo username=$GIT_USERNAME
    echo password=$GIT_PASSWORD
}
git_credential_fill | git credential approve
# CODE_DIR (/karavan/code) ships NON-EMPTY in the in-app-built devmode image
# (kamelets/ layer) and `git clone` refuses a non-empty target — clone the build
# source into its own fresh directory instead.
SRC_DIR=${CODE_DIR}-src
rm -rf $SRC_DIR
git clone --depth 1 --branch $GIT_BRANCH $GIT_REPOSITORY $SRC_DIR

cd $SRC_DIR/$PROJECT_ID

# Per-project Camel version (camel.jbang.camelVersion in application.properties) wins
# over the devmode image default, so each project can build on its own Camel version.
PROJECT_CAMEL_VERSION=$(grep -E "^camel.jbang.camelVersion=" application.properties 2>/dev/null | head -1 | cut -d= -f2)
CAMEL_VERSION=${PROJECT_CAMEL_VERSION:-$CAMEL_VERSION}

# Build the project image for the cluster's node architecture. The build pod runs
# on the same node pool as the workloads, so `uname -m` is the target arch. Without
# this jib defaults to linux/amd64 and the image fails on arm64 (Graviton) nodes
# with "exec /usr/bin/java: exec format error".
case "$(uname -m)" in
  aarch64|arm64) JIB_PLATFORM=linux/arm64 ;;
  *)             JIB_PLATFORM=linux/amd64 ;;
esac

# Pull the jib base image from the in-cluster registry, NOT gcr.io. The build pod
# has no egress to gcr.io ("Getting manifest for base image gcr.io/distroless/java21
# ... Network is unreachable"), so the default base must be mirrored once into the
# internal registry (registry/distroless/java21) — see deploy/eks/mirror-base-image.sh.
JIB_BASE_IMAGE=${IMAGE_REGISTRY}/distroless/java21

echo "Exporting project to runtime: ${CAMEL_RUNTIME}"
if [ "${CAMEL_RUNTIME}" = "quarkus" ]; then
  # Quarkus needs its version + gav explicitly; read them from the project's properties.
  QV=$(grep -E '^camel.jbang.quarkusVersion=' application.properties 2>/dev/null | head -1 | cut -d= -f2)
  GAV=$(grep -E '^camel.jbang.gav=' application.properties 2>/dev/null | head -1 | cut -d= -f2)
  jbang -Dcamel.jbang.version=$CAMEL_VERSION camel@apache/camel export --runtime=quarkus \
    ${QV:+--quarkus-version=$QV} ${GAV:+--gav=$GAV} --local-kamelet-dir=$KAMELETS_DIR
else
  jbang -Dcamel.jbang.version=$CAMEL_VERSION camel@apache/camel export --runtime=${CAMEL_RUNTIME} --local-kamelet-dir=$KAMELETS_DIR
fi

JIB_VERSION=3.4.6
JKUBE_VERSION=1.19.0
TARGET_IMAGE=${IMAGE_REGISTRY}/${IMAGE_GROUP}/${PROJECT_ID}:${TAG}

case "${CAMEL_RUNTIME}" in
  quarkus)
    # Quarkus builds the image with its own container-image-jib extension and deploys
    # via the quarkus-kubernetes extension (using the build pod's service account),
    # NOT jib-maven-plugin/jkube. fast-jar layout => entrypoint `java -jar quarkus-run.jar`.
    mvn clean package -DskipTests \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.push=true \
      -Dquarkus.container-image.builder=jib \
      -Dquarkus.jib.base-jvm-image=${JIB_BASE_IMAGE} \
      -Dquarkus.jib.platforms=${JIB_PLATFORM} \
      -Dquarkus.container-image.registry=${IMAGE_REGISTRY} \
      -Dquarkus.container-image.group=${IMAGE_GROUP} \
      -Dquarkus.container-image.name=${PROJECT_ID} \
      -Dquarkus.container-image.tag=${TAG} \
      -Dquarkus.container-image.insecure=true \
      -Dquarkus.container-image.username=${IMAGE_REGISTRY_USERNAME} \
      -Dquarkus.container-image.password=${IMAGE_REGISTRY_PASSWORD} \
      -Djib.allowInsecureRegistries=true \
      -Dquarkus.kubernetes.deploy=true \
      -Dquarkus.kubernetes.namespace=${NAMESPACE} \
      -Dquarkus.kubernetes.service-account=karavan \
      -Dquarkus.kubernetes.image-pull-policy=Always \
      -Dquarkus.kubernetes.labels.\"app.kubernetes.io/runtime\"=camel \
      -Dquarkus.kubernetes.labels.\"org.apache.camel.karavan/runtime\"=quarkus \
      -Dquarkus.kubernetes.labels.\"org.apache.camel.karavan/projectId\"=${PROJECT_ID} \
      -Dquarkus.kubernetes.labels.\"org.apache.camel.karavan/type\"=packaged
    ;;
  spring-boot)
    # The spring-boot export emits a Spring-Boot-loader fat jar but adds NEITHER jib NOR
    # jkube to the pom (unlike camel-main). So invoke both by full coordinates: jib builds
    # + pushes the image in EXPLODED mode (raw target/classes at /app/classes + flat deps;
    # jib infers the @SpringBootApplication main class, which runs fine from a flat
    # classpath), then jkube generates + applies the k8s manifests for the pushed image.
    mvn package \
      com.google.cloud.tools:jib-maven-plugin:${JIB_VERSION}:build \
      org.eclipse.jkube:kubernetes-maven-plugin:${JKUBE_VERSION}:resource \
      org.eclipse.jkube:kubernetes-maven-plugin:${JKUBE_VERSION}:apply \
      -DskipTests \
      -Djib.containerizingMode=exploded \
      -Djib.from.image=${JIB_BASE_IMAGE} \
      -Djib.from.platforms=${JIB_PLATFORM} \
      -Djib.allowInsecureRegistries=true \
      -Djib.to.image=${TARGET_IMAGE} \
      -Djib.to.auth.username=${IMAGE_REGISTRY_USERNAME} \
      -Djib.to.auth.password=${IMAGE_REGISTRY_PASSWORD} \
      -Djkube.skip.build=true \
      -Djkube.namespace=${NAMESPACE} \
      -Djkube.imagePullPolicy=Always \
      -Djkube.image.name=${TARGET_IMAGE}
    ;;
  *)
    # camel-main: the export emits a Spring-Boot-loader fat jar AND sets jib
    # containerizingMode=packaged. That combination puts the main class under
    # BOOT-INF/classes (off the plain classpath jib launches with) -> the container
    # crash-loops "Could not find or load main class ...CamelApplication". Forcing jib
    # EXPLODED makes it use the raw target/classes (-> /app/classes) + flat deps
    # (-> /app/libs); the entrypoint then resolves the main class. jkube (already in the
    # generated pom) applies the k8s manifests.
    # Invoke jib + jkube by FULL COORDINATES: Camel 4.18 exports no longer add
    # the jib/jkube plugins to the generated pom, so bare prefixes (jib:build,
    # k8s:apply) fail with NoPluginFoundForPrefixException.
    mvn package \
      com.google.cloud.tools:jib-maven-plugin:${JIB_VERSION}:build \
      org.eclipse.jkube:kubernetes-maven-plugin:${JKUBE_VERSION}:resource \
      org.eclipse.jkube:kubernetes-maven-plugin:${JKUBE_VERSION}:apply \
      -DskipTests \
      -Djib.containerizingMode=exploded \
      -Djkube.namespace=${NAMESPACE} \
      -Djkube.imagePullPolicy=Always \
      -Djkube.skip.build=true \
      -Djkube.image.name=${TARGET_IMAGE} \
      -Djib.from.image=${JIB_BASE_IMAGE} \
      -Djib.from.platforms=${JIB_PLATFORM} \
      -Djib.allowInsecureRegistries=true \
      -Djib.to.image=${TARGET_IMAGE} \
      -Djib.to.auth.username=${IMAGE_REGISTRY_USERNAME} \
      -Djib.to.auth.password=${IMAGE_REGISTRY_PASSWORD}
    ;;
esac
