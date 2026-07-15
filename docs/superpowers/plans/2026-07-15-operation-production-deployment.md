# Operation Production Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish and deploy `operation-service` so the System > 운영관리 console reaches its dedicated Spring API and PostgreSQL database through a narrow Gateway route.

**Architecture:** Extend the existing staged Gateway topology: `/api/ops` becomes a second specific Ingress rule beside `/api/account`, while general `/api` remains on `lemuel-service`. A dedicated Operation Deployment uses `operation-db-service/lemuel_operation`, shared JWT/internal secrets, and Redpanda; Prometheus polling and anomaly scanning remain fail-safe disabled.

**Tech Stack:** GitHub Actions, Docker Buildx, Kubernetes YAML, ArgoCD plain-directory manifests, Spring Cloud Gateway, PostgreSQL 17, Node.js built-in test runner, Gradle.

## Global Constraints

- Preserve DB-per-service: Operation connects only to `jdbc:postgresql://operation-db-service:5432/lemuel_operation`.
- Reuse `lemuel-secret`; never add plaintext credentials or print live secret values.
- Set `APP_SECURITY_INTERNAL_KEY_REQUIRED="true"` because `/api/ops/webhook/**` is publicly routable and the internal Bearer key must fail closed.
- Keep `OPS_PROMETHEUS_ENABLED="false"` and `OPS_ANOMALY_ENABLED="false"`; no Kubernetes Prometheus Service is deployed.
- Keep `APP_KAFKA_ENABLED="true"` and `SPRING_KAFKA_BOOTSTRAP_SERVERS="redpanda:29092"`.
- Route only `/api/ops` through Gateway in this extension; `/api/account` and general `/api` retain their existing targets.
- Do not seed incidents, connect directly to production PostgreSQL, or modify Operation domain/consumer code.
- Treat the existing Kafka consumer ACK/idempotency limitations and single-node Redpanda/hostPath storage as documented follow-ups, not lossless production guarantees.
- Do not modify tracked `.superpowers/sdd/*-report.md` files; write scratch reports only under ignored `.omc/logs/`.

---

### Task 1: Publish the Operation image

**Files:**
- Modify: `scripts/k8s/test/production-topology.test.mjs`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/backend-image-emergency.yml`

**Interfaces:**
- Consumes: root Dockerfile `MODULE=operation-service` support and existing workflow matrix parser.
- Produces: `ghcr.io/myoungsoo7/settlement-operation:*` from both normal and emergency workflows.

- [ ] **Step 1: Extend the expected executable image matrix first**

Add Operation to the exact expected array used for both publishing jobs:

```javascript
const expectedImages = [
  ['order-service', ''],
  ['settlement-service', '-settlement'],
  ['gateway-service', '-gateway'],
  ['account-service', '-account'],
  ['operation-service', '-operation'],
];
```

Keep the assertions scoped to `backend-ghcr` and emergency `build-push`; retain checks for `push: true`, `${{ matrix.module }}`, registry, repository image prefix, and metadata image expression.

- [ ] **Step 2: Run RED**

Run: `node --test scripts/k8s/test/production-topology.test.mjs`

Expected: the normal and emergency publishing tests fail because Operation is missing; all non-workflow topology tests pass.

- [ ] **Step 3: Add Operation to both matrices**

Add the same executable entry after Account in both workflows:

```yaml
          - module: operation-service
            image_suffix: "-operation"
```

Update both four-image comments to five-image comments and document `operation-service → settlement-operation` without changing the smoke job's honest deployed-revision semantics.

- [ ] **Step 4: Run GREEN and parse workflows**

```powershell
node --test scripts/k8s/test/production-topology.test.mjs
python -c "import sys,yaml; yaml.safe_load(open(sys.argv[1], encoding='utf-8'))" .github/workflows/ci.yml
python -c "import sys,yaml; yaml.safe_load(open(sys.argv[1], encoding='utf-8'))" .github/workflows/backend-image-emergency.yml
```

The Node suite and both PyYAML syntax checks must pass. Do not treat a GitHub workflow as a Kubernetes object.

- [ ] **Step 5: Commit**

```text
ci(images): publish operation service

Constraint: Keep normal and emergency image matrices identical
Confidence: high
Scope-risk: moderate
```

### Task 2: Deploy the Operation database and application

**Files:**
- Modify: `scripts/k8s/test/production-topology.test.mjs`
- Create: `k8s/stroage/operation-db-pv.yaml`
- Create: `k8s/base/operation-configmap.yaml`
- Create: `k8s/base/operation-deployment.yaml`

**Interfaces:**
- Consumes: `lemuel-secret`, `redpanda:29092`, and the Operation image from Task 1.
- Produces: `operation-db-service:5432` and `operation-service:8080`.

- [ ] **Step 1: Add failing Operation resource assertions**

Extend the topology test to require these kind/name pairs:

```javascript
[
  ['PersistentVolume', 'operation-db-pv'],
  ['PersistentVolumeClaim', 'operation-db-pvc'],
  ['StatefulSet', 'operation-db'],
  ['Service', 'operation-db-service'],
  ['ConfigMap', 'operation-config'],
  ['Deployment', 'operation-app'],
  ['Service', 'operation-service'],
]
```

Also assert the exact Operation image, database URL/name, Redpanda address, ports `8080`, JWT issuer, explicit false monitoring toggles, `APP_SECURITY_INTERNAL_KEY_REQUIRED: "true"`, Operation's `lemuel-secret` reference, and `/data/k8s/operation-db` single-node warning.

- [ ] **Step 2: Run RED**

Run: `node --test scripts/k8s/test/production-topology.test.mjs`

Expected: the new Operation resource test fails on missing manifests; workflow/account/Gateway tests remain green.

- [ ] **Step 3: Create the Operation database manifest**

Create `k8s/stroage/operation-db-pv.yaml` with:

```yaml
kind: PersistentVolume
metadata:
  name: operation-db-pv
spec:
  storageClassName: manual
  capacity: { storage: 20Gi }
  accessModes: [ReadWriteOnce]
  persistentVolumeReclaimPolicy: Retain
  hostPath:
    path: /data/k8s/operation-db
    type: DirectoryOrCreate
```

Add `operation-db-pvc`, PostgreSQL `17-alpine` StatefulSet `operation-db`, and headless `operation-db-service`. Use `POSTGRES_DB=lemuel_operation`, `POSTGRES_USER/PASSWORD` from `lemuel-secret`, `PGDATA=/var/lib/postgresql/data/pgdata`, the account DB resource limits, and `pg_isready` probes. State explicitly that hostPath is acceptable only for the current single-node environment.

- [ ] **Step 4: Create the exact ConfigMap**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: operation-config
  namespace: lemuel
data:
  SPRING_PROFILES_ACTIVE: "production"
  ENVIRONMENT: "production"
  SERVER_PORT: "8080"
  MANAGEMENT_SERVER_PORT: "8080"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://operation-db-service:5432/lemuel_operation?reWriteBatchedInserts=true"
  APP_KAFKA_ENABLED: "true"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "redpanda:29092"
  JWT_ISSUER: "lemuel-service"
  JWT_TTL_SECONDS: "86400"
  OPS_PROMETHEUS_ENABLED: "false"
  OPS_ANOMALY_ENABLED: "false"
  APP_SECURITY_INTERNAL_KEY_REQUIRED: "true"
```

- [ ] **Step 5: Create Operation Deployment and Service**

Create `operation-app` with image `ghcr.io/myoungsoo7/settlement-operation:latest`, `envFrom` references to `operation-config` and `lemuel-secret`, graceful shutdown `60`, GHCR pull secret, Account-equivalent rolling strategy/resources, and Actuator probes on port `8080`. Add ClusterIP `operation-service:8080` selecting `app: operation`, `tier: backend`.

- [ ] **Step 6: Run GREEN and client dry-runs**

```powershell
node --test scripts/k8s/test/production-topology.test.mjs
kubectl apply --dry-run=client --validate=false -f k8s/stroage/operation-db-pv.yaml -o name
kubectl apply --dry-run=client --validate=false -f k8s/base/operation-configmap.yaml -o name
kubectl apply --dry-run=client --validate=false -f k8s/base/operation-deployment.yaml -o name
```

Expected: topology suite passes; dry-runs list PV/PVC/StatefulSet/database Service, ConfigMap, Deployment, and application Service.

- [ ] **Step 7: Commit**

```text
feat(deploy): provision operation service and database

Constraint: Fail closed on missing webhook key and keep unavailable monitoring integrations disabled
Confidence: high
Scope-risk: moderate
```

### Task 3: Route Operation traffic through Gateway

**Files:**
- Modify: `scripts/k8s/test/production-topology.test.mjs`
- Modify: `k8s/base/gateway-configmap.yaml`
- Modify: `k8s/ingress/ingress.yaml`

**Interfaces:**
- Consumes: `operation-service:8080` from Task 2.
- Produces: path-scoped `/api/ops → gateway-service → operation-service` routing.

- [ ] **Step 1: Add failing route assertions**

Require:

```yaml
OPERATION_SERVICE_URI: "http://operation-service:8080"
```

Use the existing path-block helper to assert `/api/ops` targets `gateway-service:8080`, appears before general `/api`, `/api/account` remains Gateway, and general `/api` remains `lemuel-service:8080`. Retain negative checks for blanket `/admin` and Image Updater annotations.

- [ ] **Step 2: Run RED**

Run: `node --test scripts/k8s/test/production-topology.test.mjs`

Expected: Operation routing test fails because Gateway URI and Ingress path are absent.

- [ ] **Step 3: Implement the Gateway URI and narrow path**

Add `OPERATION_SERVICE_URI` to `gateway-config`. Insert:

```yaml
- path: /api/ops
  pathType: Prefix
  backend:
    service:
      name: gateway-service
      port:
        number: 8080
```

Place it with the other narrow Gateway paths before `/api`. Do not change `/api`, `/auth`, order, settlement, or administrator SPA targets.

- [ ] **Step 4: Run GREEN and dry-runs**

```powershell
node --test scripts/k8s/test/production-topology.test.mjs
kubectl apply --dry-run=client --validate=false -f k8s/base/gateway-configmap.yaml -o name
kubectl apply --dry-run=client --validate=false -f k8s/ingress/ingress.yaml -o name
```

Expected: all tests pass; ConfigMap and Ingress parse successfully.

- [ ] **Step 5: Commit**

```text
feat(deploy): route operation API through gateway

Rejected: Route all API paths | undeployed upstreams would regress
Confidence: high
Scope-risk: moderate
```

### Task 4: Verify and review the extended branch

**Files:**
- No tracked edits unless verification or independent review identifies a scoped defect.

**Interfaces:**
- Consumes: Tasks 1–3 plus the existing Account/Gateway deployment work.
- Produces: fresh build, test, manifest, scope, and review evidence.

- [ ] **Step 1: Run the full topology suite and YAML checks**

Run the Node topology test, parse both workflow YAML files, and client-dry-run every new/changed Operation, Gateway, Ingress, and Argo manifest. Expected: zero failures.

- [ ] **Step 2: Run Operation tests and boot JAR from scratch**

```powershell
.\gradlew.bat :operation-service:test :operation-service:bootJar --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL`, zero failed tests, and a non-plain boot JAR containing `BOOT-INF`.

- [ ] **Step 3: Verify scope and whitespace**

Run `git diff --check` from the Operation design commit through HEAD, `git status --short`, and a name/status diff. Expected: only planned Operation deployment/CI/test/spec/plan files plus previously approved Account/Gateway files; no tracked report or unrelated user files.

- [ ] **Step 4: Obtain independent task and whole-branch reviews**

Each task must receive spec-compliance and quality approval. The final reviewer must explicitly check webhook fail-closed security, secret scope, narrow routing, disabled unavailable monitoring integrations, executable CI image publishing, and preservation of the previously approved Account/Gateway topology.

- [ ] **Step 5: Record post-rollout checks without executing production writes**

After image publication and Argo reconciliation, the operator must verify Operation DB/app readiness, confirm populated Secret keys without printing values, call the list and summary endpoints with an ADMIN token, and refresh `/admin/system/operation`. Do not connect directly to PostgreSQL and do not create/modify incidents solely for smoke testing.
