# Operation Production Deployment Design

## Goal

Make the System > 운영관리 console use the real Spring `operation-service` in production instead of the current unrelated Express upstream, while preserving the account-only staged Gateway rollout already implemented on this branch.

## Confirmed Root Cause

The frontend calls `/api/ops/incidents` and `/api/ops/incidents/summary`. Local Compose supplies an Operation database, `operation-service`, Gateway `OPERATION_SERVICE_URI`, and the `/api/ops/**` Gateway route. Production CI and Kubernetes currently supply none of the Operation image, database, Deployment, Service, Gateway URI, or narrow Ingress rule. An authenticated remote request reaches an Express service and returns `401`, proving the failure occurs before Operation data or seed state is relevant.

## Scope

- Publish `operation-service` from normal and emergency backend image workflows.
- Provision a retained PostgreSQL instance dedicated to `lemuel_operation`.
- Deploy `operation-service` and expose it through a ClusterIP Service.
- Configure Gateway with the Operation Kubernetes Service URI.
- Give `/api/ops` a narrow, higher-priority Ingress rule targeting Gateway while general `/api` remains unchanged.
- Extend the existing production topology regression test and keep it required in CI.
- Run Operation module tests, boot JAR build, Kubernetes client dry-runs, and a separate review pass.

This change does not seed incidents, access a production database directly, deploy a Kubernetes monitoring stack, or route all APIs through Gateway.

## Request Path

```text
/admin/system/operation
  -> frontend SPA
  -> GET /api/ops/incidents and /api/ops/incidents/summary
  -> Ingress /api/ops
  -> gateway-service
  -> operation-service
  -> operation-db-service/lemuel_operation
```

The existing explicit `/admin/system` frontend route continues to protect direct page refreshes. `/api/ops` is more specific than `/api`, so only Operation traffic moves to Gateway.

## CI Images

Both backend publishing matrices add:

```yaml
- module: operation-service
  image_suffix: "-operation"
```

The resulting production image is `ghcr.io/myoungsoo7/settlement-operation:latest`. The existing workflow topology test must verify the normal and emergency executable matrices, metadata image expression, push setting, and `MODULE` build argument.

## Kubernetes Resources

### Operation database

`k8s/stroage/operation-db-pv.yaml` follows the existing single-node settlement/account database pattern:

- `operation-db-pv`: retained `20Gi` hostPath `/data/k8s/operation-db`
- `operation-db-pvc`
- `operation-db` PostgreSQL 17 StatefulSet
- headless `operation-db-service:5432`
- database `lemuel_operation`
- username/password from `lemuel-secret`
- `pg_isready` liveness/readiness probes

The hostPath limitation is explicit: before multi-node use, replace it with node-bound or CSI-backed storage and establish backup/restore procedures.

### Operation configuration

`operation-config` contains:

```yaml
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

Kafka consumption remains enabled because Redpanda already exists in the Kubernetes manifests. Prometheus polling and anomaly scanning remain explicitly disabled: there is no production Kubernetes Prometheus Service in this repository, and enabling a nonexistent upstream would create misleading monitoring behavior. The internal-key requirement is fail-closed because `/api/ops/webhook/**` is publicly reachable through the narrow route and its Bearer internal key is the webhook's authentication boundary.

### Operation application

`operation-app` uses `ghcr.io/myoungsoo7/settlement-operation:latest`, imports `operation-config` and `lemuel-secret`, and exposes port `8080`. It needs the shared secret because it validates `JWT_SECRET`, connects using PostgreSQL credentials, and protects the Alertmanager webhook with `INTERNAL_API_KEY`. The Deployment follows the Account/Settlement rolling strategy, resource limits, graceful shutdown, GHCR pull secret, and Actuator startup/liveness/readiness probes. `operation-service` exposes it as ClusterIP port `8080`.

### Gateway and Ingress

Gateway configuration adds:

```yaml
OPERATION_SERVICE_URI: "http://operation-service:8080"
```

Ingress adds `/api/ops` before the general `/api` rule and targets `gateway-service:8080`. The `/api/account` narrow rule remains unchanged, and `/api`, `/auth`, order, and settlement paths retain their current backends.

## Empty State and Error Handling

An empty Operation database is valid. The list response is an empty page and the summary contains zero counts; the UI must render this instead of reporting a query error. Flyway/JPA/database failures keep the Pod unready. Missing Operation upstreams remain visible as Gateway errors and are caught by topology and post-rollout checks.

Alertmanager webhook ingestion is available only when callers use the shared internal token. Prometheus-derived metrics and anomaly-created incidents remain absent until a later monitoring-stack deployment explicitly enables those features.

Before rollout, the operator must verify through Kubernetes Secret metadata/configuration—not by printing secret values—that live `lemuel-secret` entries for `JWT_SECRET` and `INTERNAL_API_KEY` are populated. The checked-in SealedSecret contains placeholders and cannot prove live values.

Existing Operation metric consumers acknowledge records without the repository-wide `processed_events` idempotency defense. This task does not modify those consumers, but the deployment must not be described as lossless: duplicate or failed metric ingestion can skew buckets. With anomaly scanning disabled, this does not automatically create incidents; consumer hardening is a separate required follow-up before enabling anomaly automation. The single Redpanda broker is also a demo/single-node availability boundary.

## Verification

Repository verification must:

1. Demonstrate the topology regression test fails before Operation resources are added and passes afterward.
2. Verify both image workflows publish the Operation image.
3. Verify the Operation PV/PVC/StatefulSet/database Service, ConfigMap, application Deployment/Service, Gateway URI, and path-scoped `/api/ops` Ingress rule.
4. Verify the Operation Deployment uses `lemuel-secret`, Prometheus/anomaly features are disabled, and missing internal webhook keys fail closed.
5. Parse all changed YAML with Kubernetes client dry-run.
6. Run `:operation-service:test` and `:operation-service:bootJar`.
7. Obtain independent task and whole-branch review approval.

Post-rollout verification uses authenticated HTTP and Kubernetes health/log evidence only. It must not connect directly to PostgreSQL.

## Success Criteria

- Authenticated ADMIN requests to `/api/ops/incidents` and `/api/ops/incidents/summary` return `200` from Spring Operation service.
- Empty incident state renders zero/empty values without an error toast.
- Direct navigation to `/admin/system/operation` returns the frontend SPA.
- Account routing and all general API routes remain unchanged.
- Normal and emergency CI publish the Operation image.
- Operation database and application Pods become ready after Argo reconciliation.
