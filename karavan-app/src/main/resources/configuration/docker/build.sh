#!/usr/bin/env bash
# Fail fast: stop at the first failing step so the build log shows the real cause.
set -eo pipefail

# Camel runtime to build, injected per-project from its application.properties
# (camel.jbang.runtime). camel-main and spring-boot share the jib-exploded path;
# quarkus uses the Quarkus container-image extension. Defaults to camel-main.
CAMEL_RUNTIME=${CAMEL_RUNTIME:-camel-main}
# jib invoked by full coordinates (not in exported poms since Camel 4.18)
JIB_VERSION=3.4.6

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

# Build for the host architecture so the image runs where it's deployed (jib
# defaults to linux/amd64 -> "exec format error" on arm64 hosts).
case "$(uname -m)" in
  aarch64|arm64) JIB_PLATFORM=linux/arm64 ;;
  *)             JIB_PLATFORM=linux/amd64 ;;
esac

echo "Exporting project to runtime: ${CAMEL_RUNTIME}"
if [ "${CAMEL_RUNTIME}" = "quarkus" ]; then
  QV=$(grep -E '^camel.jbang.quarkusVersion=' application.properties 2>/dev/null | head -1 | cut -d= -f2)
  GAV=$(grep -E '^camel.jbang.gav=' application.properties 2>/dev/null | head -1 | cut -d= -f2)
  jbang -Dcamel.jbang.version=$CAMEL_VERSION camel@apache/camel export --runtime=quarkus \
    ${QV:+--quarkus-version=$QV} ${GAV:+--gav=$GAV} --local-kamelet-dir=$KAMELETS_DIR
else
  jbang -Dcamel.jbang.version=$CAMEL_VERSION camel@apache/camel export --runtime=${CAMEL_RUNTIME} --local-kamelet-dir=$KAMELETS_DIR
fi

case "${CAMEL_RUNTIME}" in
  quarkus)
    # Quarkus container-image-jib build + push. The app starts the container from the
    # pushed image separately (docker mode), so no kubernetes deploy here.
    mvn clean package -DskipTests \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.push=true \
      -Dquarkus.container-image.builder=jib \
      -Dquarkus.jib.platforms=${JIB_PLATFORM} \
      -Dquarkus.container-image.registry=${IMAGE_REGISTRY} \
      -Dquarkus.container-image.group=${IMAGE_GROUP} \
      -Dquarkus.container-image.name=${PROJECT_ID} \
      -Dquarkus.container-image.tag=${TAG} \
      -Dquarkus.container-image.insecure=true \
      -Dquarkus.container-image.username=${IMAGE_REGISTRY_USERNAME} \
      -Dquarkus.container-image.password=${IMAGE_REGISTRY_PASSWORD} \
      -Djib.allowInsecureRegistries=true
    ;;
  spring-boot)
    # spring-boot export adds no jib plugin to the pom -> invoke jib by full coordinates.
    # EXPLODED so the main class is at /app/classes (not BOOT-INF/classes in the fat jar).
    mvn package com.google.cloud.tools:jib-maven-plugin:3.4.6:build \
      -DskipTests \
      -Djib.containerizingMode=exploded \
      -Djib.from.platforms=${JIB_PLATFORM} \
      -Djib.allowInsecureRegistries=true \
      -Djib.to.image=$IMAGE_REGISTRY/$IMAGE_GROUP/$PROJECT_ID:$TAG \
      -Djib.to.auth.username=$IMAGE_REGISTRY_USERNAME \
      -Djib.to.auth.password=$IMAGE_REGISTRY_PASSWORD
    ;;
  *)
    # camel-main: invoke jib by FULL COORDINATES (Camel 4.18 exports no longer add
    # the plugin to the pom -> bare jib:build prefix fails). Force EXPLODED so the
    # main class lands at /app/classes (not BOOT-INF/classes inside the fat jar),
    # else the container crash-loops with ClassNotFoundException. See kubernetes/build.sh.
    mvn package \
      com.google.cloud.tools:jib-maven-plugin:${JIB_VERSION}:build \
      -DskipTests \
      -Djib.containerizingMode=exploded \
      -Djib.from.platforms=${JIB_PLATFORM} \
      -Djib.allowInsecureRegistries=true \
      -Djib.to.image=$IMAGE_REGISTRY/$IMAGE_GROUP/$PROJECT_ID:$TAG \
      -Djib.to.auth.username=$IMAGE_REGISTRY_USERNAME \
      -Djib.to.auth.password=$IMAGE_REGISTRY_PASSWORD
    ;;
esac
