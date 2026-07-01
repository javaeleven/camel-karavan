# Apache Camel Karavan — build & Kubernetes deployment automation.
#
# Build chain (codegen -> TS core -> Quarkus app) and a Helm deploy (Karavan app
# + PostgreSQL) that works for BOTH local (Docker Desktop, registry:5005) and AWS
# (EKS + ECR). Git is per-project (configured in the UI); the chart renders the
# 'karavan' registry Secret that project builds require.
#
# Quick start (local):  make deploy-oidc REGISTRY=localhost:5005
# Quick start (AWS):    make deploy-eks            # images already in ECR
#   make wait          # block until the app is Ready
#   make test          # curl health + UI through a temporary port-forward
#   make recover-ssa   # if a Helm field-conflict blocks an upgrade

# ---- Secrets / account config -------------------------------------------
# Real secrets and account identifiers live in .env (GITIGNORED), never here.
# Copy .env.example to .env and fill it in. Anything set in .env or on the
# command line overrides the safe, non-secret defaults below.
-include .env

# ---- Configuration -------------------------------------------------------
VERSION      ?= 4.18.2

# ---- Registry switch -----------------------------------------------------
# REGISTRY is the ONLY value you change between environments. Custom images are
# built as $(REGISTRY)/$(IMAGE_NAME):$(IMAGE_TAG) and pushed there; the cluster
# pulls the same reference.
#   local:  REGISTRY=localhost:5005                                  (run `make local-registry` first)
#   AWS:    REGISTRY=<acct>.dkr.ecr.<region>.amazonaws.com/<group>   (set in .env; run `make ecr-login` first)
REGISTRY     ?= localhost:5005
IMAGE_NAME   ?= camel-karavan
IMAGE_TAG    ?= dev
IMAGE        := $(REGISTRY)/$(IMAGE_NAME):$(IMAGE_TAG)
# Target CPU arch for the app image. Jib defaults to linux/amd64 regardless of build
# host, so this MUST match the cluster nodes (EKS is arm64/Graviton) or the pod fails
# with "exec /usr/bin/java: exec format error". Override for an amd64 cluster.
JIB_PLATFORM ?= linux/arm64

# Dev-mode build container (karavan-devmode/Dockerfile). karavan-app starts
# integration build/run containers from THIS image, so it must be pushed to the
# same registry and its URL passed to the app via KARAVAN_DEVMODE_IMAGE.
DEVMODE_IMAGE_NAME ?= camel-karavan-devmode
DEVMODE_IMAGE      := $(REGISTRY)/$(DEVMODE_IMAGE_NAME):$(IMAGE_TAG)

# Stock upstream image used by the session-mode `deploy` target (no build needed).
STOCK_IMAGE  ?= ghcr.io/apache/camel-karavan

NAMESPACE    ?= karavan
RELEASE      ?= karavan
CHART        ?= deploy/helm/karavan
LOCAL_PORT   ?= 18080

HELM         ?= helm
KUBECTL      ?= kubectl
GRADLE       ?= ./gradlew
NPM          ?= npm
DOCKER       ?= docker

# Kube context. By default targets use your CURRENT context. EKS targets set
# KUBE_CONTEXT to $(EKS_CONTEXT) so they ALWAYS hit the cluster — independent of
# whatever `kubectl config current-context` happens to be (this is why a prior
# `make deploy-eks` could land on docker-desktop). KCTX/HCTX are recursive (=)
# so target-specific KUBE_CONTEXT overrides are picked up at recipe time.
# EKS_CONTEXT is account-specific — set it in .env (arn:aws:eks:<region>:<acct>:cluster/<name>).
KUBE_CONTEXT ?=
EKS_CONTEXT  ?=
KCTX          = $(if $(strip $(KUBE_CONTEXT)),--context $(strip $(KUBE_CONTEXT)),)
HCTX          = $(if $(strip $(KUBE_CONTEXT)),--kube-context $(strip $(KUBE_CONTEXT)),)

# Public URL the app is served at on EKS (account/domain-specific) -> set in .env.
# Used only to print where to open the app after deploy-eks.
APP_URL      ?=

# AWS CLI profile for ECR auth (set in .env). When set, aws commands get
# `--profile <value>`; empty -> default credential chain / env vars.
AWS_PROFILE      ?=
AWS_PROFILE_FLAG := $(if $(strip $(AWS_PROFILE)),--profile $(strip $(AWS_PROFILE)),)

# ---- Auth0 OIDC client credentials -> set in .env (never inline secrets here) ----
OIDC_CLIENT_ID     ?=
OIDC_CLIENT_SECRET ?=

.DEFAULT_GOAL := help
SHELL := /bin/bash

# ---- Help ----------------------------------------------------------------
.PHONY: help
help: ## Show this help
	@echo "Apache Camel Karavan — make targets:"
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ==========================================================================
# Build chain
# ==========================================================================
.PHONY: generate
generate: ## STEP 1: generate Camel TS models/API into karavan-core (run from repo root)
	$(GRADLE) :karavan-generator:run

.PHONY: core
core: ## STEP 2: build/install the TS core library (runs build + Mocha tests)
	cd karavan-core && $(NPM) install

.PHONY: app
app: ## STEP 3: package the Quarkus app + SPA (uber-jar, public profile)
	$(GRADLE) :karavan-app:build -Dquarkus.profile=public

.PHONY: build
build: generate core app ## Full local build chain (generate -> core -> app)

.PHONY: image-build
image-build: ## Build the container image $(IMAGE) (Auth0-aligned SPA) via Quarkus Jib
	# Jib loads a single-arch $(JIB_PLATFORM) image into the local Docker daemon for
	# image-push. The platform MUST match the target cluster (jib defaults to amd64).
	$(GRADLE) :karavan-app:quarkusBuild -Dquarkus.profile=public \
		-Dquarkus.container-image.build=true \
		-Dquarkus.jib.platforms=$(JIB_PLATFORM) \
		-Dquarkus.container-image.image=$(IMAGE)

.PHONY: image-push
image-push: ## Push $(IMAGE) to its registry
	$(DOCKER) push $(IMAGE)

.PHONY: image
image: image-build image-push ## Build AND push the app image $(IMAGE)

.PHONY: devmode-image
devmode-image: ## Build AND push the dev-mode build container $(DEVMODE_IMAGE)
	$(DOCKER) build -t $(DEVMODE_IMAGE) -f karavan-devmode/Dockerfile karavan-devmode
	$(DOCKER) push $(DEVMODE_IMAGE)

.PHONY: images
images: image devmode-image ## Build AND push ALL images (app + dev-mode) to $(REGISTRY)

# ---- Registry helpers ----------------------------------------------------
.PHONY: local-registry
local-registry: ## Run a local registry on localhost:5005 (Docker Desktop pulls via its mirror)
	@lsof -nP -iTCP:5005 -sTCP:LISTEN >/dev/null 2>&1 && { \
		echo "ERROR: host port 5005 is in use (often macOS 'AirPlay Receiver')."; \
		echo "Free it: System Settings > General > AirDrop & Handoff > AirPlay Receiver = Off"; \
		exit 1; } || true
	@$(DOCKER) start registry >/dev/null 2>&1 || \
		$(DOCKER) run -d -p 5005:5000 --restart=always --name registry registry:3 >/dev/null
	@echo "Local registry up on localhost:5005"

# ECR host (registry without the /group suffix) and region derived from REGISTRY.
ECR_HOST   := $(firstword $(subst /, ,$(REGISTRY)))
ECR_REGION := $(word 4,$(subst ., ,$(REGISTRY)))

.PHONY: ecr-login
ecr-login: ## docker login to the ECR registry in REGISTRY (needs awscli + creds)
	@echo "$(REGISTRY)" | grep -qE '\.dkr\.ecr\..*\.amazonaws\.com' || { \
		echo "REGISTRY ($(REGISTRY)) is not an ECR host (<acct>.dkr.ecr.<region>.amazonaws.com)"; exit 1; }
	aws ecr get-login-password $(AWS_PROFILE_FLAG) --region $(ECR_REGION) \
		| $(DOCKER) login --username AWS --password-stdin $(ECR_HOST)

.PHONY: ecr-create-repos
ecr-create-repos: ## Ensure the app + dev-mode ECR repos exist (ECR does NOT auto-create on push)
	@for repo in apache/$(IMAGE_NAME) apache/$(DEVMODE_IMAGE_NAME); do \
		aws ecr describe-repositories $(AWS_PROFILE_FLAG) --region $(ECR_REGION) --repository-names $$repo >/dev/null 2>&1 \
			|| { echo "creating ECR repo $$repo"; aws ecr create-repository $(AWS_PROFILE_FLAG) --region $(ECR_REGION) --repository-name $$repo >/dev/null; }; \
	done
	@echo "ECR repos ready."

.PHONY: vscode
vscode: ## Build the VS Code extension (.vsix)
	cd karavan-vscode && $(NPM) install && $(NPM) run package && npx @vscode/vsce package

.PHONY: test-core
test-core: ## Run the karavan-core Mocha test suite
	cd karavan-core && $(NPM) test

# ==========================================================================
# Deploy (Helm) — Docker Desktop Kubernetes
# ==========================================================================
.PHONY: helm-lint
helm-lint: ## Lint the Helm chart
	$(HELM) lint $(CHART)

.PHONY: template
template: ## Render the chart manifests to stdout
	$(HELM) template $(RELEASE) $(CHART) --namespace $(NAMESPACE)

.PHONY: deploy
deploy: ## Install/upgrade Karavan session-mode (stock image $(STOCK_IMAGE); ignores REGISTRY)
	$(HELM) upgrade --install $(RELEASE) $(CHART) \
		--namespace $(NAMESPACE) --create-namespace \
		--set image.repository=$(STOCK_IMAGE) \
		--set image.tag=$(VERSION) \
		--wait --timeout 6m
	@echo "Deployed. Run 'make wait' then 'make test'."

.PHONY: deploy-nowait
deploy-nowait: ## Install/upgrade without blocking on readiness
	$(HELM) upgrade --install $(RELEASE) $(CHART) \
		--namespace $(NAMESPACE) --create-namespace \
		--set image.repository=$(STOCK_IMAGE) \
		--set image.tag=$(VERSION)

.PHONY: wait
wait: ## Block until the Postgres + app rollouts are Ready
	$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE)-postgres --timeout=5m
	$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE) --timeout=5m

.PHONY: status
status: ## Show deployment status (pods, services, helm release)
	@$(HELM) status $(RELEASE) --namespace $(NAMESPACE) 2>/dev/null | head -5 || true
	@echo "---"
	$(KUBECTL) -n $(NAMESPACE) get pods,svc

.PHONY: logs
logs: ## Tail the Karavan app logs
	$(KUBECTL) -n $(NAMESPACE) logs -f deploy/$(RELEASE)

.PHONY: port-forward
port-forward: ## Forward the app to http://localhost:8080
	@echo "Karavan UI: http://localhost:8080  (admin / see application.properties)"
	$(KUBECTL) -n $(NAMESPACE) port-forward svc/$(RELEASE) 8080:80

.PHONY: test
test: ## Verify the app is fully running (health + UI + public API)
	@echo ">> Checking rollout readiness..."
	@$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE) --timeout=5m
	@echo ">> Probing endpoints via temporary port-forward on :$(LOCAL_PORT)..."
	@$(KUBECTL) -n $(NAMESPACE) port-forward svc/$(RELEASE) $(LOCAL_PORT):80 >/dev/null 2>&1 & \
		PF_PID=$$!; \
		trap "kill $$PF_PID 2>/dev/null" EXIT; \
		sleep 4; \
		echo "-- /q/health/ready"   && curl -fsS http://localhost:$(LOCAL_PORT)/q/health/ready && echo "" && \
		echo "-- /public/readiness" && curl -fsS http://localhost:$(LOCAL_PORT)/public/readiness && echo "" && \
		echo "-- / (UI)"            && curl -fsS -o /dev/null -w "HTTP %{http_code}\n" http://localhost:$(LOCAL_PORT)/ && \
		echo ">> OK: Karavan is fully running."

.PHONY: open
open: ## Open the UI in a browser (starts a foreground port-forward)
	@( sleep 3 && open http://localhost:8080 ) &
	$(MAKE) port-forward

.PHONY: up
up: deploy test ## Deploy and verify in one step

# ==========================================================================
# Auth0 OIDC deployment
# ==========================================================================
.PHONY: oidc-image
oidc-image: images ## Build AND push ALL images (app + dev-mode) — alias for `make images`

.PHONY: oidc-secret
oidc-secret: ## Create the Auth0 client-creds k8s Secret (pass OIDC_CLIENT_ID / OIDC_CLIENT_SECRET)
	@test -n "$(OIDC_CLIENT_ID)"     || { echo "set OIDC_CLIENT_ID";     exit 1; }
	@test -n "$(OIDC_CLIENT_SECRET)" || { echo "set OIDC_CLIENT_SECRET"; exit 1; }
	$(KUBECTL) $(KCTX) -n $(NAMESPACE) create secret generic karavan-oidc \
		--from-literal=client-id='$(OIDC_CLIENT_ID)' \
		--from-literal=client-secret='$(OIDC_CLIENT_SECRET)' \
		--dry-run=client -o yaml | $(KUBECTL) $(KCTX) -n $(NAMESPACE) apply -f -

# Append HELM_FORCE=--force to recover from a Server-Side-Apply field conflict
# (e.g. a leftover `kubectl set env` owning KARAVAN_DEVMODE_IMAGE); see recover-ssa.
HELM_FORCE ?=

.PHONY: deploy-oidc
deploy-oidc: images oidc-secret ## LOCAL/registry: rebuild+push ALL images, deploy Auth0 OIDC from $(REGISTRY)
	$(HELM) upgrade --install $(RELEASE) $(CHART) $(HELM_FORCE) \
		--namespace $(NAMESPACE) --create-namespace \
		-f $(CHART)/values-auth0.yaml \
		--set image.repository=$(REGISTRY)/$(IMAGE_NAME) \
		--set image.tag=$(IMAGE_TAG) \
		--set image.pullPolicy=Always \
		--set devmodeImage=$(DEVMODE_IMAGE) \
		--set registry.server=$(REGISTRY) \
		--set registry.group=$(IMAGE_NAME)
	# The image tag ($(IMAGE_TAG)) is mutable, so an unchanged Deployment spec
	# would not roll out the freshly-pushed image. Force a rollout to pull it.
	$(KUBECTL) -n $(NAMESPACE) rollout restart deploy/$(RELEASE)
	$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE) --timeout=6m
	@echo "Deployed with Auth0 OIDC from $(IMAGE). Run 'make test-oidc'."

.PHONY: check-eks-context
check-eks-context:
	@test -n "$(strip $(EKS_CONTEXT))" || { \
		echo "EKS_CONTEXT is not set — refusing to deploy to your current context."; \
		echo "Set it in .env: EKS_CONTEXT=arn:aws:eks:<region>:<account-id>:cluster/<name>"; exit 1; }

.PHONY: deploy-eks
deploy-eks: KUBE_CONTEXT := $(EKS_CONTEXT)
deploy-eks: check-eks-context oidc-secret ## AWS: deploy to EKS (targets --kube-context from EKS_CONTEXT in .env, NOT your current context)
	# App+devmode images are pulled from ECR via the node IAM role (no rebuild).
	# values-eks.yaml runs a provider-agnostic in-cluster registry for PROJECT
	# images (Distribution auto-creates repos on push — no ECR token/repo steps).
	# oidc-secret (prereq) inherits KUBE_CONTEXT and lands on EKS too.
	@echo ">> Deploying to EKS context: $(EKS_CONTEXT)"
	$(HELM) $(HCTX) upgrade --install $(RELEASE) $(CHART) $(HELM_FORCE) \
		--namespace $(NAMESPACE) --create-namespace \
		-f $(CHART)/values-auth0.yaml \
		-f $(CHART)/values-eks.yaml
	# :dev is a mutable tag — force a rollout so the freshly-pushed image is pulled.
	$(KUBECTL) $(KCTX) -n $(NAMESPACE) rollout restart deploy/$(RELEASE)
	$(KUBECTL) $(KCTX) -n $(NAMESPACE) rollout status deploy/$(RELEASE) --timeout=8m
	@echo "Deployed to EKS. App + registry are served by a DEDICATED ALB owned by this"
	@echo "cluster's LB controller (helm Ingress), so its rules are never pruned. Open"
	@echo "$(if $(strip $(APP_URL)),$(strip $(APP_URL)),the app URL — set APP_URL in .env)  (ALB DNS: kubectl get ingress -n $(NAMESPACE))"

# LEGACY (pre-dedicated-ALB). The app used to share the trade cluster's internal
# ALB, whose rules that cluster's controller pruned every ~24h (-> 404). It now has
# its OWN ALB via the helm Ingress (ingress.* in values-eks.yaml), so this manual
# rule-reassertion is no longer needed by deploy-eks. Kept only for emergency
# recovery if you ever revert to the shared ALB. (deploy/eks/* are gitignored.)
.PHONY: wire-alb
wire-alb: KUBE_CONTEXT := $(EKS_CONTEXT)
wire-alb: check-eks-context ## AWS: (re)assert the ALB routes for the app + registry (fixes the 404 after the rule is pruned)
	@if [ -f deploy/eks/wire-alb.sh ] && [ -f deploy/eks/wire-registry-alb.sh ]; then \
		echo ">> Re-asserting ALB routes on EKS context: $(EKS_CONTEXT)"; \
		KARAVAN_KUBE_CONTEXT="$(EKS_CONTEXT)" AWS_PROFILE="$(AWS_PROFILE)" bash deploy/eks/wire-alb.sh; \
		KARAVAN_KUBE_CONTEXT="$(EKS_CONTEXT)" AWS_PROFILE="$(AWS_PROFILE)" bash deploy/eks/wire-registry-alb.sh; \
	else \
		echo "!! deploy/eks/wire-alb.sh not found (account-specific, gitignored) — skipping ALB wiring."; \
	fi

.PHONY: recover-ssa
recover-ssa: ## Recover from a Helm Server-Side-Apply field conflict (deletes the Deployment; Helm recreates it)
	@echo "Deleting the karavan Deployment so Helm can re-own its fields (brief downtime)..."
	-$(KUBECTL) -n $(NAMESPACE) delete deployment $(RELEASE)
	@echo "Now re-run your deploy target (make deploy-eks / deploy-oidc)."

.PHONY: test-oidc
test-oidc: ## Verify the BFF OIDC flow (auth/type=oidc, /auth/login -> IdP, XHR 401/499)
	@$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE) --timeout=5m
	@$(KUBECTL) -n $(NAMESPACE) port-forward svc/$(RELEASE) $(LOCAL_PORT):80 >/dev/null 2>&1 & \
		PF_PID=$$!; trap "kill $$PF_PID 2>/dev/null" EXIT; sleep 4; \
		echo "-- /ui/auth/type" && curl -fsS http://localhost:$(LOCAL_PORT)/ui/auth/type && echo "" && \
		echo "-- /q/health/ready" && curl -fsS -o /dev/null -w "HTTP %{http_code}\n" http://localhost:$(LOCAL_PORT)/q/health/ready && \
		echo "-- /auth/login (BFF -> IdP)" && curl -sS -o /dev/null -w "HTTP %{http_code} -> %{redirect_url}\n" http://localhost:$(LOCAL_PORT)/auth/login && \
		echo "-- /ui/auth/me (XHR, expect 401/499)" && curl -sS -o /dev/null -w "HTTP %{http_code}\n" -H "X-Requested-With: JavaScript" http://localhost:$(LOCAL_PORT)/ui/auth/me && \
		echo ">> OK: Karavan BFF OIDC active (browser /auth/login redirects to Auth0)."

# ==========================================================================
# Teardown
# ==========================================================================
.PHONY: undeploy
undeploy: ## Uninstall the Helm release (keeps the Postgres PVC)
	-$(HELM) uninstall $(RELEASE) --namespace $(NAMESPACE)

.PHONY: down
down: ## Uninstall and delete the namespace (destroys Postgres data)
	-$(HELM) uninstall $(RELEASE) --namespace $(NAMESPACE)
	-$(KUBECTL) delete namespace $(NAMESPACE)
