# Local Kubernetes deployment

Self-contained install of Apache Camel Karavan (the app + PostgreSQL) on a local
Kubernetes cluster — built for **Docker Desktop Kubernetes**, driven by a Helm
chart (`deploy/helm/karavan`) and the repo-root `Makefile`.

## What it deploys

| Resource                                    | Purpose                                                                          |
|---------------------------------------------|----------------------------------------------------------------------------------|
| `karavan` Deployment                        | The Quarkus control-plane + React SPA (`ghcr.io/apache/camel-karavan:<version>`) |
| `karavan-postgres` Deployment + PVC         | PostgreSQL for application state (Flyway-migrated at startup)                    |
| `karavan` ServiceAccount + Role/RoleBinding | Lets the app watch/manage pods, services & deployments in its namespace          |
| `karavan` Service (NodePort)                | Exposes the app on port 80 → container 8080                                      |

**Git is per-project.** There is no global/Gitea repository: each project is
configured (by the user who creates it) with its own repository + branch via
Create/Edit Project → *Fetch branches*, and is cloned/pushed/pulled with that
user's credentials from **System → Git**. A project's remote is restricted to its
owner. Projects with no remote are local-only. This keeps the install
self-contained: **Postgres is the only external dependency**, and the app reaches
readiness without provisioning a git server.

## Prerequisites

- Docker Desktop with Kubernetes enabled (`kubectl config current-context` → `docker-desktop`)
- `helm` and `kubectl` on your PATH

## Deploy

```bash
make deploy     # helm upgrade --install into the 'karavan' namespace, waits for readiness
make test       # curl /q/health/ready, /public/readiness and the UI through a port-forward
```

Or in one step: `make up`.

## Access the UI

This cluster's NodePort is **not** auto-mapped to `localhost` (Docker Desktop's
multi-node kind-based clusters don't do that), so use a port-forward:

```bash
make port-forward          # http://localhost:8080
# or
make open                  # port-forward + open the browser
```

Human login is **SSO-only** (OIDC via the Backend-for-Frontend flow — see the Auth0
section below). There is no built-in username/password login; deploy with
`auth.type: "oidc"` and the `oidc` values filled in.

## Operate

```bash
make status                # pods, services, helm release
make logs                  # tail the app logs
make undeploy              # remove the release (keeps the Postgres PVC)
make down                  # remove the release AND the namespace (destroys data)
```

## The REGISTRY switch (custom images)

The cluster **cannot use host-built images directly** (`imagePullPolicy: Never`
fails on Docker Desktop's kind nodes), so any custom image must be **pushed to a
registry the cluster can pull from**. A single `REGISTRY` variable is the only
thing you change between environments — the image is always built and deployed as
`$(REGISTRY)/$(IMAGE_NAME):$(IMAGE_TAG)` (default `localhost:5005/camel-karavan:auth0`):

| `REGISTRY` value | Mode | Setup |
|---|---|---|
| `localhost:5005` | **Local** (push + pull on Docker Desktop) | `make local-registry` first |
| `<account-id>.dkr.ecr.<region>.amazonaws.com` | **Remote** (push + pull via AWS ECR) | `make ecr-login` first |

`make image` (and its alias `make oidc-image`) **builds AND pushes** in one step —
no separate `docker push` is needed. `make deploy-oidc` depends on `image`, so
**every deploy rebuilds and pushes** to `$(REGISTRY)`.

### Local registry (Docker Desktop)

```bash
make local-registry        # runs registry:2 on localhost:5005
```

> macOS caveat: host port 5005 is often held by **AirPlay Receiver**. Free it via
> *System Settings → General → AirDrop & Handoff → AirPlay Receiver = Off*, then
> rerun `make local-registry`. Docker Desktop's kind nodes pull `localhost:5005/*`
> through their built-in registry mirror, which proxies the host registry.

### Remote registry (AWS ECR)

```bash
make ecr-login REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
```

ECR is private, so add a pull secret and reference it:
`--set image.pullSecrets[0].name=ecr-creds` (or `image.pullSecrets` in values).

Run `make help` for the full target list.

## Auth0 OIDC — Backend-for-Frontend (BFF)

Karavan can authenticate against **Auth0** (OIDC) instead of the built-in session
login, using the **Backend-for-Frontend** pattern: **Quarkus** runs the OIDC
Authorization Code flow server-side and keeps the tokens in an **encrypted
session cookie**; the SPA **never handles a token**. This avoids the SPA/PKCE
redirect loop entirely. (Needs a **custom image** — the stock image has no
`/auth/login` endpoint and a session-only SPA.)

How it fits together:

| Layer | What happens |
|---|---|
| Backend | `quarkus-oidc` `application-type=web-app`: issuer `https://<your-oidc-issuer>`, requests an access token for audience `https://<your-api-audience>/`, reads roles from the `https://<your-oidc-issuer>/roles` claim, and exposes `/auth/login` (start flow), `/auth/callback` (redirect-path), `/logout`. Tokens are split across encrypted session cookies. |
| Frontend | Cookie-only. It calls `/ui/*` with `withCredentials` + `X-Requested-With`; a 401/499 triggers a **full-page** navigation to `/auth/login` (Quarkus does the IdP redirect). Roles come from `/ui/auth/me` (built from the server-side identity), so UI gating matches backend `@RolesAllowed`. |
| Secret | `client-id` / `client-secret` live in the **`karavan-oidc` k8s Secret**, injected via `secretKeyRef`. Never in git or Helm values. |

Flow: SPA → `/ui/auth/me` (XHR) → **499** → SPA navigates to `/auth/login` → Quarkus **302 → Auth0** → `/auth/callback` (sets cookie) → back to the SPA → `/ui/auth/me` **200**. Logout → `/logout` → Auth0 `/v2/logout`.

Config (non-secret) lives in `deploy/helm/karavan/values-auth0.yaml`.

### Deploy with Auth0

```bash
# 0. (local) start the local registry; (ECR) log in
make local-registry                                  # local, OR:
# make ecr-login REGISTRY=<acct>.dkr.ecr.<region>.amazonaws.com

# 1. Store the Auth0 client credentials in a k8s Secret
make oidc-secret OIDC_CLIENT_ID=<id> OIDC_CLIENT_SECRET=<secret>

# 2. Build+push the custom image AND deploy (one switch: REGISTRY)
make deploy-oidc REGISTRY=localhost:5005             # local, OR:
# make deploy-oidc REGISTRY=<acct>.dkr.ecr.<region>.amazonaws.com

# 3. Verify
make test-oidc      # auth/type=oidc, /auth/login -> Auth0 302, XHR /ui/auth/me -> 499
```

> Throwaway alternative: push to `ttl.sh` (anonymous, ephemeral, public over
> HTTPS — no login, expires after the TTL): `make deploy-oidc REGISTRY=ttl.sh IMAGE_NAME=<uuid> IMAGE_TAG=24h`.

### Required Auth0 dashboard setup

BFF uses the **server-side callback**, so the Auth0 app is a **Regular Web
Application** (not SPA). In the tenant:

1. An **API** with identifier `https://<your-api-audience>/` (the audience).
2. A **Regular Web Application** (the `clientId`/secret) with, for the origin you
   browse from (e.g. `http://localhost:8080`):
   - **Allowed Callback URLs**: `http://localhost:8080/auth/callback`  ← note the `/auth/callback` path
   - **Allowed Logout URLs**: `http://localhost:8080/login`
   - (Web Origins are not needed — the browser never calls Auth0 from JS.)
3. An **Action** that adds the user's roles to the **access token** under the
   `https://<your-oidc-issuer>/roles` claim, mapped to Karavan roles
   (`platform-admin` / `platform-developer` / `platform-user`). The backend reads
   roles from the access token, so this single claim suffices.
4. Allow the **offline_access** grant (refresh tokens) for the application.

### Switch back to session auth

```bash
make deploy        # redeploys with auth.type=session (default values.yaml, stock image)
```
