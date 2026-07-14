# Gateway and Account Production Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish Gateway and Account images and deploy a production account request path that fixes the CEO account dashboard without rerouting unrelated APIs.

**Architecture:** Kubernetes gives `/api/account` a narrow, higher-priority Ingress rule to a new Gateway Deployment. Gateway forwards the request to a new Account Deployment backed by a dedicated retained PostgreSQL StatefulSet; explicit frontend rules protect CEO/admin SPA deep links. ArgoCD recursively discovers the existing nested manifest layout.

**Tech Stack:** GitHub Actions, Docker Buildx, Kubernetes YAML, ArgoCD, Spring Cloud Gateway, PostgreSQL 17, Node.js built-in test runner, Gradle.

## Global Constraints

- Preserve DB-per-service: account-service connects only to `lemuel_account` on `account-db-service`.
- Reuse `lemuel-secret`; never add plaintext credentials.
- Do not access production databases directly.
- Keep general `/api`, authentication, order, and settlement traffic on their current backend in this rollout.
- Route only `/api/account` through Gateway until the other Gateway upstreams are deployed.
- Account history remains append-only; no seed or migration data rewrite is part of this task.
- Preserve all unrelated user changes in the dirty worktree.

---

### Task 1: Add a production topology regression test

**Files:**
- Create: `scripts/k8s/test/production-topology.test.mjs`

**Interfaces:**
- Consumes: repository YAML and workflow files as text.
- Produces: `node --test` assertions that fail if CI images, account/Gateway resources, Argo recursion, or narrow Ingress routing disappear.

- [ ] **Step 1: Write the failing test**

Create a Node test using `node:test`, `node:assert/strict`, `readFileSync`, and `existsSync`. It must assert:

```javascript
assert.match(ci, /module: gateway-service[\s\S]*image_suffix: "-gateway"/);
assert.match(ci, /module: account-service[\s\S]*image_suffix: "-account"/);
assert.match(emergency, /module: gateway-service[\s\S]*image_suffix: "-gateway"/);
assert.match(emergency, /module: account-service[\s\S]*image_suffix: "-account"/);
for (const file of requiredManifests) assert.equal(existsSync(file), true, `${file} must exist`);
assert.match(argocd, /directory:\s*\n\s+recurse: true/);
assert.match(ingress, /path: \/api\/account[\s\S]*name: gateway-service/);
assert.match(ingress, /path: \/api\s*[\s\S]*name: lemuel-service/);
assert.match(ingress, /path: \/admin\/ceo[\s\S]*name: lemuel-frontend-service/);
```

The test must also assert the expected image names, JDBC URL, `MANAGEMENT_SERVER_PORT: "8080"`, Redpanda address, and `ACCOUNT_SERVICE_URI`.

- [ ] **Step 2: Run the test to verify RED**

Run: `node --test scripts/k8s/test/production-topology.test.mjs`

Expected: FAIL because Gateway/Account workflow entries and manifests do not exist.

- [ ] **Step 3: Commit the failing test**

```bash
git add scripts/k8s/test/production-topology.test.mjs
git commit -m "test(deploy): guard gateway account production topology" \
  -m "Confidence: high" -m "Scope-risk: narrow"
```

### Task 2: Publish Gateway and Account images in both workflows

**Files:**
- Modify: `.github/workflows/ci.yml:187-197`
- Modify: `.github/workflows/backend-image-emergency.yml:22-31`

**Interfaces:**
- Consumes: root `Dockerfile` `MODULE` build argument.
- Produces: `ghcr.io/myoungsoo7/settlement-gateway:*` and `ghcr.io/myoungsoo7/settlement-account:*`.

- [ ] **Step 1: Extend both image matrices**

Add the same entries after `settlement-service` in both workflows:

```yaml
          - module: gateway-service
            image_suffix: "-gateway"
          - module: account-service
            image_suffix: "-account"
```

Update nearby workflow comments so the four image mappings are explicit and remove unsupported claims that Image Updater is already picking up SHA tags.

- [ ] **Step 2: Run the focused regression test**

Run: `node --test scripts/k8s/test/production-topology.test.mjs`

Expected: workflow assertions PASS; manifest assertions remain FAIL.

- [ ] **Step 3: Commit the workflow change**

```bash
git add .github/workflows/ci.yml .github/workflows/backend-image-emergency.yml
git commit -m "ci(images): publish gateway and account services" \
  -m "Constraint: Keep normal and emergency matrices identical" \
  -m "Confidence: high" -m "Scope-risk: moderate"
```

### Task 3: Deploy Account database and service

**Files:**
- Create: `k8s/stroage/account-db-pv.yaml`
- Create: `k8s/base/account-configmap.yaml`
- Create: `k8s/base/account-deployment.yaml`

**Interfaces:**
- Consumes: `lemuel-secret`, `redpanda:29092`, GHCR account image.
- Produces: `account-db-service:5432` and `account-service:8080`.

- [ ] **Step 1: Add account database resources**

Clone the settlement DB resource pattern with unique names:

```yaml
metadata:
  name: account-db-pv
spec:
  persistentVolumeReclaimPolicy: Retain
  hostPath:
    path: /data/k8s/account-db
---
metadata:
  name: account-db-pvc
---
kind: StatefulSet
metadata:
  name: account-db
spec:
  serviceName: account-db-service
```

Use PostgreSQL `17-alpine`, `POSTGRES_DB=lemuel_account`, secret-backed username/password, `PGDATA`, `20Gi` storage, and `pg_isready` liveness/readiness probes. Finish with a headless `account-db-service`.

- [ ] **Step 2: Add account configuration**

Create `account-config` with these exact values:

```yaml
data:
  SPRING_PROFILES_ACTIVE: "production"
  SERVER_PORT: "8080"
  MANAGEMENT_SERVER_PORT: "8080"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://account-db-service:5432/lemuel_account?reWriteBatchedInserts=true"
  APP_KAFKA_ENABLED: "true"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "redpanda:29092"
  JWT_ISSUER: "lemuel-service"
  JWT_TTL_SECONDS: "86400"
```

- [ ] **Step 3: Add account Deployment and Service**

Create `account-app` using `ghcr.io/myoungsoo7/settlement-account:latest`, `account-config`, and `lemuel-secret`. Match the settlement Deployment's rolling strategy, resource bounds, image pull secret, graceful shutdown, and probes. Create `account-service` as ClusterIP port `8080`.

- [ ] **Step 4: Validate the three manifests locally**

Run:

```bash
kubectl apply --dry-run=client -f k8s/stroage/account-db-pv.yaml -o yaml >/dev/null
kubectl apply --dry-run=client -f k8s/base/account-configmap.yaml -o yaml >/dev/null
kubectl apply --dry-run=client -f k8s/base/account-deployment.yaml -o yaml >/dev/null
```

Expected: all commands exit `0` without contacting a production database.

- [ ] **Step 5: Commit account resources**

```bash
git add k8s/stroage/account-db-pv.yaml k8s/base/account-configmap.yaml k8s/base/account-deployment.yaml
git commit -m "feat(deploy): provision account service and database" \
  -m "Constraint: Reuse sealed credentials and retain account storage" \
  -m "Confidence: high" -m "Scope-risk: moderate"
```

### Task 4: Deploy Gateway and repair public routing

**Files:**
- Create: `k8s/base/gateway-configmap.yaml`
- Create: `k8s/base/gateway-deployment.yaml`
- Modify: `k8s/ingress/ingress.yaml`
- Modify: `k8s/argocd/argocd-app.yaml`

**Interfaces:**
- Consumes: `lemuel-service:8080`, `settlement-service:8080`, `account-service:8080`.
- Produces: `gateway-service:8080`, `/api/account` Gateway ingress, protected admin SPA deep links, recursive Argo discovery.

- [ ] **Step 1: Add Gateway configuration**

```yaml
data:
  SPRING_PROFILES_ACTIVE: "production"
  SERVER_PORT: "8080"
  ORDER_SERVICE_URI: "http://lemuel-service:8080"
  SETTLEMENT_SERVICE_URI: "http://settlement-service:8080"
  ACCOUNT_SERVICE_URI: "http://account-service:8080"
```

Do not invent DNS names for service modules that have no Kubernetes Service in this repository.

- [ ] **Step 2: Add Gateway Deployment and Service**

Create `gateway-app` using `ghcr.io/myoungsoo7/settlement-gateway:latest` and `gateway-config`. Match the standard rolling strategy, resources, probes, and `ghcr-secret`. Create `gateway-service` as ClusterIP port `8080`.

- [ ] **Step 3: Add narrow and SPA-specific Ingress paths**

Insert `/api/account` before `/api`, targeting `gateway-service:8080`. Keep `/api` targeting `lemuel-service:8080`. Add `/admin/ceo`, `/admin/system`, `/admin/operation`, `/admin/settlement`, and `/admin/login` Prefix paths targeting `lemuel-frontend-service:80`. Do not add a blanket `/admin → gateway-service` rule.

- [ ] **Step 4: Enable recursive Argo directory discovery**

Under `spec.source`, add:

```yaml
    directory:
      recurse: true
```

Do not add Image Updater annotations in this task: official Image Updater support expects Helm or Kustomize, while this Application remains a plain recursive directory. New Gateway/Account Deployments will pull their new `:latest` images on initial creation; migration to Kustomize and immutable image automation is separate work.

- [ ] **Step 5: Validate routing manifests**

Run:

```bash
kubectl apply --dry-run=client -f k8s/base/gateway-configmap.yaml -o yaml >/dev/null
kubectl apply --dry-run=client -f k8s/base/gateway-deployment.yaml -o yaml >/dev/null
kubectl apply --dry-run=client -f k8s/ingress/ingress.yaml -o yaml >/dev/null
kubectl apply --dry-run=client -f k8s/argocd/argocd-app.yaml -o yaml >/dev/null --validate=false
node --test scripts/k8s/test/production-topology.test.mjs
```

Expected: all commands exit `0`; topology test reports all tests passing.

- [ ] **Step 6: Commit routing resources**

```bash
git add k8s/base/gateway-configmap.yaml k8s/base/gateway-deployment.yaml k8s/ingress/ingress.yaml k8s/argocd/argocd-app.yaml
git commit -m "feat(deploy): route account API through gateway" \
  -m "Rejected: Cut over all API paths | undeployed upstreams would regress to 502" \
  -m "Confidence: high" -m "Scope-risk: moderate"
```

### Task 5: Complete build and configuration verification

**Files:**
- Modify only if verification exposes a scoped defect in Tasks 1-4.

**Interfaces:**
- Consumes: completed workflow and Kubernetes changes.
- Produces: fresh evidence that topology checks, module tests, boot JARs, and manifest parsing pass.

- [ ] **Step 1: Run topology and whitespace checks**

Run:

```bash
node --test scripts/k8s/test/production-topology.test.mjs
git diff --check HEAD~4..HEAD
```

Expected: zero failed tests and no whitespace errors.

- [ ] **Step 2: Run backend module tests**

Run: `./gradlew :gateway-service:test :account-service:test --no-daemon`

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 3: Build production artifacts**

Run: `./gradlew :gateway-service:bootJar :account-service:bootJar --no-daemon`

Expected: `BUILD SUCCESSFUL` and non-plain JARs under each module's `build/libs`.

- [ ] **Step 4: Review the final scoped diff**

Run: `git status --short` and `git diff HEAD~4..HEAD -- .github/workflows scripts/k8s k8s docs/superpowers/specs docs/superpowers/plans`

Expected: only the planned deployment files are in the task's commits; pre-existing user changes remain untouched.

- [ ] **Step 5: Post-rollout checks for the operator**

After GitHub Actions publishes images and ArgoCD syncs, verify Kubernetes rollout/readiness, then test authenticated HTTP responses for the four `/api/account` endpoints and a direct `/admin/ceo/accounts` refresh. Use Kubernetes health/log evidence only; do not connect to PostgreSQL directly.
