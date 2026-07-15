# Full MSA k3s GitOps Deployment Design

## Objective

Make every runnable Gradle service in this repository buildable, publishable, and deployable to a production-style single-node k3s cluster through an immutable GitOps flow.

The deployment inventory is the repository's fourteen Gradle service modules: thirteen domain services (`order`, `settlement`, `loan`, `financial-statements`, `economics`, `company`, `operation`, `market`, `ai`, `common-data`, `investment`, `account`, and `organization`) plus `gateway`. The frontend is deployed as a separate workload but is not counted as an MSA service.

## Chosen Approach

Use Kustomize with an immutable commit-SHA image promotion recorded in Git.

CI builds and tests the repository, publishes one GHCR image per service with the source commit SHA as the deployment tag, and then changes the production Kustomize overlay to reference that exact SHA. ArgoCD watches only the production overlay and reconciles the Git change. Mutable `latest` tags are not used for deployment.

ArgoCD Image Updater is intentionally not required. Keeping promotion in the repository makes the deployed revision auditable without adding another cluster controller or granting a controller Git write-back access.

## Repository and Kustomize Structure

`k8s/base` contains reusable workload definitions, shared infrastructure, service ConfigMaps, Services, and security defaults. `k8s/overlays/production` contains production image tags and environment-specific patches. ArgoCD targets `k8s/overlays/production` as a Kustomize application instead of recursively applying every YAML file under `k8s`.

Every service has an explicit image entry in the production overlay. The image tag value is the full Git commit SHA produced by the image build. Promotion must update all fourteen service image entries to the same source revision so a release is a coherent set.

The production overlay also includes the frontend, PostgreSQL, Redpanda, Elasticsearch, ingress, namespace, NetworkPolicies, and the two valid settlement batch resources.

## CI Pipeline

Pull requests targeting `main` or `develop`, and pushes to `main` or `develop`, run the complete backend verification:

1. Run the production topology tests.
2. Run `shared-common`'s own `check` task.
3. Run the root aggregate Gradle build, tests, and JaCoCo verification for all fourteen services.
4. Build every service container image without pushing on pull requests.
5. Render the production overlay with `kubectl kustomize`.
6. Run Kubernetes client dry-run validation over the rendered resources.
7. Verify that service inventory, image matrix, Kustomize images, Deployments, Services, Gateway routes, and required Secret keys are consistent.

On a successful `main` push, CI publishes fourteen GHCR images tagged with the full commit SHA. A promotion job then updates every production Kustomize image tag to that SHA and commits only the overlay change with `[skip ci]`. It must use a repository-scoped bot identity and the minimum `contents: write` permission required for that job.

Workflow concurrency is keyed by workflow and branch. A newer run cancels an older in-progress run so an older commit cannot publish or promote after a newer one.

SonarCloud and Snyk run only when their tokens are available. When configured, Sonar quality-gate failure and Snyk high-or-critical findings fail the job. Missing optional analysis credentials are reported explicitly and do not make fork pull requests impossible to validate.

The emergency image workflow accepts an existing Git commit SHA, checks out exactly that commit, runs behind a protected GitHub environment approval, and republishes only immutable SHA tags. It cannot publish `latest` or promote an untested working tree.

## Service and Routing Topology

Gateway is the only public backend entry point. Traefik receives public backend traffic and sends `/api`, `/auth`, and service-specific API paths to Gateway. The frontend handles `/` and explicitly defined SPA routes. Order and other domain services are never exposed through a public NodePort.

All thirteen domain services use internal `ClusterIP` Services. Gateway's production ConfigMap defines an explicit cluster-DNS upstream for every domain service; no production route may inherit a localhost fallback.

Each application Deployment has:

- a startup probe and liveness probe on `/actuator/health/liveness`;
- a readiness probe on `/actuator/health/readiness`;
- CPU and memory requests and limits sized for a single-node cluster;
- rolling update with `maxUnavailable: 0` and `maxSurge: 1`;
- `automountServiceAccountToken: false`;
- pod seccomp profile `RuntimeDefault`;
- non-root execution, privilege escalation disabled, all Linux capabilities dropped, and a read-only root filesystem where the application supports it;
- writable `emptyDir` mounts only for required temporary and log locations.

No HPA or PodDisruptionBudget is added because the approved target is one k3s node. Multi-node high availability, distributed storage, and replica scaling are a separate design.

## Data Topology

A single PostgreSQL StatefulSet is used to fit the approved single-node target. It stores data on a dynamically provisioned PVC using k3s's `local-path` StorageClass. Static manual `hostPath` PersistentVolumes are removed.

Every domain service receives a distinct PostgreSQL database and database user. Services may share the PostgreSQL server process, but they do not share schemas, credentials, JPA entities, queries, or application-level database access. Order and settlement remain logically isolated and communicate only through the existing Kafka projection and internal reconciliation API rules.

Database initialization is idempotent and creates the service databases and roles before applications start. Flyway remains owned by each service and migrates only its own database. Schema changes must remain backward compatible with the rolling deployment strategy.

Redpanda and Elasticsearch retain single-node storage appropriate to the approved target. Their limitations and backup/restore procedures are documented in the deployment runbook.

## Secret Management

The repository does not commit plaintext secrets or empty SealedSecret placeholders. The production overlay references Kubernetes Secrets that an operator must create before the ArgoCD application is enabled. A checked-in template lists keys but contains no values, and a validation script fails with a precise missing-secret or missing-key message.

Required application secrets include:

- service-specific PostgreSQL usernames and passwords;
- `JWT_SECRET`;
- `INTERNAL_API_KEY`;
- `PAYOUT_ENC_KEY`;
- `CHAT_ENC_KEY`;
- `TOSS_SECRET_KEY`;
- GHCR pull credentials when the packages are private;
- optional mail, Slack, and third-party API credentials only when their corresponding features are enabled.

Secret-dependent features must not silently fall back to insecure production behavior. Required encryption, payment, authentication, and internal API keys cause pre-deployment validation to fail when absent.

## Network and k3s Integration

Ingress uses k3s's Traefik ingress class. NGINX-specific annotations and installation requirements are removed. TLS configuration is represented as a production overlay setting and must reference an operator-provisioned certificate Secret before public production use.

Default-deny NetworkPolicies are applied to application and data workloads. Explicit policies allow Traefik to Gateway, Gateway to domain services, application-to-PostgreSQL, application-to-Redpanda, approved Elasticsearch clients, DNS, and required external egress. The policies must match the CNI capabilities enabled on the target k3s installation; the runbook records the required k3s network-policy controller state.

## Batch Jobs

The invalid `createSettlementJob` CronJob is removed because no such Spring Batch job exists. The settlement confirmation CronJob uses the settlement-service image and invokes only the existing `confirmSettlementJob` definition. A second settlement batch resource may be present only when its corresponding job implementation and a failing-then-passing topology test are added.

CronJobs use immutable promoted images, `concurrencyPolicy: Forbid`, bounded history limits, a deadline, resource limits, and the same Secret/ConfigMap contract as settlement-service.

## Promotion, Failure Handling, and Rollback

Build, image publication, and promotion are separate gates. A build or image failure leaves the production overlay unchanged. A promotion commit is created only after all fourteen images for the same SHA exist.

ArgoCD sync and rollout verification runs only from a protected deployment environment with cluster credentials. Without those credentials, ordinary CI still validates build, images, and rendered manifests, but does not claim that deployment occurred.

After promotion, verification waits for ArgoCD sync and Kubernetes rollout completion, then checks Gateway health and representative order, settlement, account, operation, investment, and organization routes. A failed rollout is rolled back by reverting the promotion commit, restoring the previous coherent SHA set. The deployment runbook contains the exact verification and revert procedure.

## Testing Strategy

Topology tests are inventory-driven. They derive the expected service set from one checked-in deployment inventory and assert that all other representations match it. Tests cover:

- all fourteen Gradle modules;
- all fourteen CI image matrix entries;
- all fourteen production Kustomize image entries;
- thirteen domain Deployments and internal Services plus Gateway;
- Gateway upstreams for every domain service;
- Traefik ingress routing only to Gateway and frontend;
- probes, resources, selectors, immutable tags, and security contexts;
- required Secret names and keys;
- PostgreSQL database/user isolation;
- settlement CronJob image and job-name correctness;
- absence of `latest`, public backend NodePorts, empty SealedSecrets, localhost production upstreams, and manual hostPath PVs.

Configuration changes follow red-green-refactor: first extend a topology or workflow test so it fails for the missing invariant, then implement the smallest manifest or workflow change, then run the focused test and the full topology suite.

Final verification requires the complete Gradle build, `shared-common` check, topology tests, Kustomize render, client dry-run, Docker builds for all images, and an independent review. Live rollout and smoke evidence are required before claiming the k3s environment itself is deployed successfully.

## Scope Boundaries

This design does not add multi-node high availability, an external secrets operator, a PostgreSQL operator, distributed storage, automatic horizontal scaling, service mesh, or cross-region disaster recovery. It prepares a production-style single-node k3s deployment with explicit prerequisites and rollback behavior without representing single-node infrastructure as highly available.
