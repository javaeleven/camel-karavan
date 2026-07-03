# Devmode image built in-app with Jib Core

## Problem

The devmode/build container image (`karavan-devmode`) was a **pre-built artifact**:
built by CI or `make devmode-image` from `karavan-devmode/Dockerfile`, pushed to a
registry, and referenced by `karavan.devmode.image`. That means:

- every environment needs an out-of-band build+push step (Docker required),
- air-gapped/ECR-only clusters must mirror `ghcr.io` manually,
- the image's Camel/JBang versions are hard-coded at its build time and drift from
  the platform's `karavan.camel.version`.

## Approach: programmatic image derivation with Jib Core

[`jib-core`](https://github.com/GoogleContainerTools/jib/tree/master/jib-core) is a
Java SDK that assembles and pushes OCI images **without a Docker daemon** — it
works from inside the control-plane pod on Kubernetes.

Jib cannot execute Dockerfile `RUN` steps (it has no build containers), so the
image is assembled from layers instead:

```
base maven:3.9-eclipse-temurin-21   (ships Maven + JDK 21 + git + openssh-client)
  ├─ layer: portable JBang distribution (downloaded once, unpacked to /karavan/.jbang)
  ├─ layer: jbang trusted-sources.json for the Apache Camel catalog
  │         (replaces the interactive `jbang trust add`)
  ├─ layer: writable /karavan dirs (.m2, code, builder) owned 1001:0, g+rwX
  ├─ ENV   CAMEL_VERSION / JBANG_JAVA_OPTIONS / JBANG_DIR / PATH … (platform config)
  ├─ CMD   jbang camel@apache/camel run --source-dir=/karavan/code --console
  └─ push to the platform registry (karavan.container-image.* credentials)
```

Note: the retired Dockerfile's `apt-get install git` was redundant — the official
maven image already ships git and openssh-client.

The result is pushed to `karavan.devmode.image`, so everything downstream
(DockerForKaravan, KubernetesService, build pods) keeps working unchanged — the
image is simply *materialized by the app itself* instead of by CI.

What this buys:

- **No Docker anywhere in the loop** — build+push happens in-process (registry API).
- **Registry-local image** — clusters pull from their own registry (e.g. ECR), not ghcr.
- **Version coherence** — `CAMEL_VERSION`/`camel.jbang.version` in the image follow
  `karavan.camel.version`; bumping platform config re-derives the image.
- Per-project `camel.jbang.camelVersion` still overrides at run time as before.

## Configuration

```properties
# Build + push the devmode image at startup (default ON — the pre-built pipeline
# karavan-devmode/Dockerfile + docker-devmode.yml CI was removed).
karavan.devmode.build.enabled=true
karavan.devmode.build.base-image=maven:3.9-eclipse-temurin-21
karavan.devmode.build.jbang-version=0.139.3
# Target ref (pull for devmode/build containers AND push target of the in-app build):
karavan.devmode.image=${KARAVAN_DEVMODE_IMAGE:registry:5000/karavan/karavan-devmode:4.18.2}
```

Registry credentials for the **push** come from the existing
`karavan.container-image.registry-username/-password`; pulls of the base use
anonymous or the same credentials when the base lives in the same registry.
`http://`-style local registries (registry:5000) are handled via
`setAllowInsecureRegistries(true)`.

## Implementation

- `service/image/DevmodeImageService.java` — jib-core derivation:
  `Jib.from(base) → env/labels/cmd → containerize(to(target))`, async at startup
  (`StartupLoader`), non-blocking, logged via the jib event stream. Failures are
  logged and do not abort startup (same behavior as a missing pre-built image).
- `KaravanConfig.Devmode.Build` — typed config (above) with defaults.
- Dependency `com.google.cloud.tools:jib-core` (version in `gradle.properties`).

## Limitations / future work

- No Camel dependency pre-warm (that was a `RUN` in the old Dockerfile): the first
  devmode/build run downloads Camel artifacts. Enable the shared `.m2` volume/PVC
  (`karavan.devmode.createm2=true`) so subsequent runs are warm.
- The JBang zip is fetched once from GitHub releases (cached in the app tmp dir);
  air-gapped installs can pre-place the zip or serve it from an internal mirror
  (future: configurable download URL).
- Rebuild happens at each startup (jib layer caching makes repeats cheap); a REST
  trigger + UI status can be added later.
