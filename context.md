# Project Context

Orientation for humans and agents working on this repo. Read this first — it
captures the non-obvious facts so you don't have to re-derive them. This is a
**customized fork** of Apache Camel Karavan (version **4.18.2**) with a
Maven→Gradle build, Jib images, and Helm/Auth0 deployment tooling added.

Companion docs: `structure.md` (deeper code map), `start.md` (run walkthrough),
`styling.md` (how to change the designer UI), `temp.txt` (scratch commands).

---

## 1. What this project is

**Apache Camel Karavan** is a low-code integration platform: a visual designer
for Apache Camel plus a control plane that **builds and runs the integrations you
design as containers (Docker) or pods (Kubernetes).**

Karavan does not run integrations in-process. When you press "run," the app tells
Docker/Kubernetes to launch a separate **dev-mode container** (Maven + JBang +
Camel) that compiles and hot-runs your integration and streams logs back. So a
working setup always needs three things: the **Karavan app**, a **PostgreSQL**
database (app state), and an **image registry** (stores images Karavan builds).

### Modules (monorepo)
| Module | What it is | Build |
|--------|-----------|-------|
| `karavan-generator` | Java tool that generates the TS Camel model from the Camel catalog into `karavan-core` | Gradle (`:karavan-generator`) |
| `karavan-core` | Shared TS model + Camel-YAML API library | npm (`cd karavan-core && npm install`) |
| `karavan-app` | **The main app**: Quarkus backend + React SPA (the designer). This is where 95% of work happens | Gradle (`:karavan-app`) |
| `karavan-devmode` | Dockerfile for the per-project build/run container the app launches | `docker build` |
| `karavan-vscode` | VS Code extension (reuses the designer) | npm |

> **Important:** the React **designer source now lives inside the app** at
> `karavan-app/src/main/webui/src/ui/features/project/designer/`. The top-level
> `karavan-designer/` folder only holds generated metadata now.

### Tech stack
- Backend: **Quarkus 3.37**, **Java 21**, Hibernate ORM Panache + PostgreSQL +
  Flyway, quarkus-oidc, quarkus-jgit, docker-java, kubernetes-client.
- Frontend: **React 19 + TypeScript + Vite**, PatternFly 6, served/bundled by
  **Quinoa** (runs the Vite build during the Quarkus build).
- Build: **Gradle 9.6.1** (`./gradlew`, modules in `settings.gradle`, versions in
  `gradle.properties`). Images via **Jib** (no Dockerfile for the app).

---

## 2. Development

Everything runs from the **repo root** in a **Java 21** shell.

### Run in dev mode (the normal loop — live reload)
```bash
# 1. Postgres (app state)
docker compose -f docs/install/karavan-docker/docker-compose.yaml up -d postgres

# 2. App with hot reload
QUARKUS_PROFILE=public ./gradlew :karavan-app:quarkusDev
```
- App/UI: <http://localhost:8080> · SPA dev server (HMR): <http://localhost:3003> · debugger: `5005`
- Login (session auth): **admin / K@r@v@n418**
- Edit any `.tsx`/`.css` under `karavan-app/src/main/webui/src/` → the browser
  updates live via **HMR, no restart**. Java edits → Quarkus reloads on next request.
- `karavan-app/.env` (gitignored) points the datasource at `localhost:5432` with
  `karavan/karavan` creds. Without it the app tries host `postgres` (only resolvable
  inside the compose network) and Flyway fails with `UnknownHostException: postgres`.

### Occasional commands
```bash
./gradlew :karavan-generator:run     # regenerate Camel TS model (only when Camel catalog version changes)
cd karavan-core && npm install       # rebuild the core TS lib (needed for VS Code, NOT for the app)
./gradlew :karavan-app:quarkusBuild  # package the app (no container)
```
For pure frontend/backend edits you do **not** need `:karavan-generator:run` or the
core npm build — the app doesn't depend on `karavan-core` (the SPA vendors its own copy).

### Running integrations locally
The app launches `ghcr.io/apache/camel-karavan-devmode:4.18.2`. That tag is **not
published** for this fork, so build it once locally (else "run" fails with
`manifest unknown`):
```bash
docker build -t ghcr.io/apache/camel-karavan-devmode:4.18.2 -f karavan-devmode/Dockerfile karavan-devmode
```
The app finds it in the local Docker daemon (pull policy `ifNotExists`).

### Where things live
- Designer UI (nodes/arrows/styling): `karavan-app/src/main/webui/src/ui/features/project/designer/route/`
  — see `styling.md`.
- Backend REST/services: `karavan-app/src/main/java/org/apache/camel/karavan/{api,service,docker,kubernetes}/`
- Runtime config: `karavan-app/src/main/resources/application.properties`
- DB schema (Flyway): `karavan-app/src/main/resources/database/`

---

## 3. Deployment

The deployed app is **not** live source — the SPA is compiled by a **production
Vite build and baked into a Jib image**. Source edits only reach a deployment by
rebuilding the image. Deploy config/automation lives in the **`Makefile`** and
**`deploy/helm/karavan/`**. `make help` lists all targets.

### Build & push the app image (Jib)
```bash
make image-build REGISTRY=localhost:5005 JIB_PLATFORM=linux/amd64   # build only
make image       REGISTRY=localhost:5005 JIB_PLATFORM=linux/amd64   # build + push
make images      REGISTRY=localhost:5005 JIB_PLATFORM=linux/amd64   # app + devmode images
```
- **`JIB_PLATFORM` matters**: the Makefile default is `linux/arm64`. On amd64 hosts
  you MUST pass `JIB_PLATFORM=linux/amd64` or the container fails with
  `exec format error`. The platform must match the target cluster's nodes.
- `make image-build` runs Gradle with `clean --rerun-tasks --no-build-cache` and
  prunes the dangling old image. This is **required**: otherwise Gradle marks
  `quarkusBuild` UP-TO-DATE, skips the Quinoa/Vite rebuild, and ships a **stale
  frontend** — the classic "my change works in dev but not on deploy" bug.

### Deploy targets (Helm)
| Target | What it does |
|--------|--------------|
| `make deploy` | Session-mode, **stock upstream image** (no custom code — won't contain your changes) |
| `make deploy-oidc` | LOCAL k8s: build+push your images, deploy Auth0 OIDC from `REGISTRY`, force rollout |
| `make deploy-eks` | AWS EKS (targets `EKS_CONTEXT` from `.env`, Auth0) |
| `make undeploy` / `make down` | Uninstall / delete namespace |

Secrets/account config live in `.env` at the repo root (gitignored; copy from
`.env.example`) — `OIDC_CLIENT_ID/SECRET`, `REGISTRY`, `EKS_CONTEXT`, `AWS_PROFILE`.

### Helm chart (`deploy/helm/karavan/`)
Deploys the app + PostgreSQL + an in-cluster registry (+ optional ALB ingress for
EKS). Auth mode `session` or `oidc`. In k8s the app runs in **Kubernetes mode**
(talks to the API server, not a Docker socket).

### Run the prod image locally without k8s (Docker)
Two options:
1. **Docker Compose** (`docs/install/karavan-docker/docker-compose.yaml`): app +
   postgres + registry. Note it points at the stock image — swap `image:` to your
   built one to test your changes.
2. **Bare container** (fast build-vs-deploy check), against the compose Postgres:
   ```bash
   docker run -d --name karavan-test -p 8081:8080 --network karavan \
     -v /var/run/docker.sock:/var/run/docker.sock \
     --group-add "$(getent group docker | cut -d: -f3)" \
     -e KARAVAN_DATASOURCE_URL=jdbc:postgresql://postgres:5432/karavan_test \
     -e KARAVAN_DATASOURCE_USERNAME=karavan -e KARAVAN_DATASOURCE_PASSWORD=karavan \
     localhost:5005/camel-karavan:dev
   ```
   The **Docker socket mount is required** — in Docker mode the app touches the
   daemon at startup; without it startup aborts and the container exits.

### Verify a change reached the image (no login needed)
```bash
base=http://localhost:8081
{ curl -s "$base/" | grep -oE '/assets/[^"]+\.css'
  for js in $(curl -s "$base/" | grep -oE '/assets/[^"]+\.js'); do
    curl -s "$base$js" | grep -oE '[A-Za-z0-9._-]+\.css' | sed 's#^#/assets/#'; done
} | sort -u | while read css; do curl -s "$base$css" | grep -o 'SOME_TOKEN_FROM_YOUR_CHANGE'; done
```

---

## 4. Gotchas & conventions (things that cost time)

- **Build tool is Gradle, not Maven.** Old docs / `mvn`/`mvnw` references are stale.
  Use `./gradlew`. Dev = `:karavan-app:quarkusDev`, package = `:karavan-app:quarkusBuild`.
- **Stale SPA on build** → always build the image via `make image-build` (forces
  `clean`/`--rerun-tasks`). Plain `quarkusBuild` can skip the frontend rebuild.
- **Jib platform** → pass `JIB_PLATFORM=linux/amd64` on amd64 hosts.
- **Dev-mode image** must be built locally (see §2) or "run" fails `manifest unknown`.
- **Postgres 18 image** stores data in a version subdir — the compose volume mounts
  `/var/lib/postgresql` (the parent), not `/var/lib/postgresql/data`.
- **Auth over http**: the session cookie is `Secure` (`AuthResource.java`), so login
  only "sticks" over HTTPS or on `http://localhost` in a Chromium browser. Over plain
  http elsewhere, `/ui/auth/me` returns 401 even after a successful login. Production
  works because it's served over HTTPS.
- **Admin password is seeded once** into Postgres (`access_state` table) and persists;
  it isn't re-read from config on later starts. >5 failed logins locks the account.
  To reset for a test: use a fresh DB, or `DELETE FROM access_state;` and restart.
- **Inline styles override CSS** in the designer (e.g. node spacing is `marginTop`
  in `DslElement.tsx`, not CSS). See `styling.md`.
- **Config precedence**: env vars > `.env` > `application.properties`. Key envs:
  `KARAVAN_DATASOURCE_*`, `PLATFORM_AUTH` (`session`|`oidc`), `KARAVAN_DEVMODE_IMAGE`.
- **Credentials**: session mode → `admin` / `K@r@v@n418` (`platform.password`).

---

## 5. Quick reference

```bash
# DEV
docker compose -f docs/install/karavan-docker/docker-compose.yaml up -d postgres
KARAVAN_DEVMODE_BUILD_ENABLED=false QUARKUS_PROFILE=public,local ./gradlew :karavan-app:quarkusDev          # http://localhost:8080

# BUILD IMAGE (fresh SPA guaranteed)
make image-build REGISTRY=localhost:5005 JIB_PLATFORM=linux/amd64
docker images localhost:5005/camel-karavan:dev                   # confirm CREATED = now

# DEPLOY (local k8s, Auth0)
make deploy-oidc REGISTRY=localhost:5005 JIB_PLATFORM=linux/amd64

# HELP
make help
```
