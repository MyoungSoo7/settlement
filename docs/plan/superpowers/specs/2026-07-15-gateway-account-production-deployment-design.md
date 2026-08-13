# Gateway and Account Production Deployment Design

## Goal

Make the production Kubernetes topology match the working local request path for the CEO account dashboard: browser → frontend/Ingress → gateway-service → account-service → dedicated account PostgreSQL.

## Scope

- Add `gateway-service` and `account-service` to both normal and emergency backend image workflows.
- Provision a dedicated `lemuel_account` PostgreSQL instance with retained storage.
- Deploy `gateway-service` and `account-service` with stable ClusterIP Services.
- Route `/api/account/**` through `gateway-service` as the first production Gateway cutover.
- Route the known `/admin` SPA prefixes explicitly to the frontend so CEO deep links cannot be captured by a broader backend rule.
- Make ArgoCD discover manifests in the repository's nested `k8s/` directories.
- Add static deployment checks that fail when the required images, routes, or resources disappear.

This change does not seed account entries, alter account domain logic, access a production database directly, or deploy the other currently absent service modules.

## Architecture

The Ingress gives `/api/account` a more-specific rule targeting the `gateway-service` ClusterIP while retaining the existing `/api` rule to `lemuel-service`. The gateway forwards `/api/account/**` to `account-service`. The account service connects only to `account-db-service` and consumes Redpanda events using the existing shared JWT secret.

Ingress defines frontend rules for `/admin/ceo`, `/admin/system`, `/admin/operation`, `/admin/settlement`, and `/admin/login`, with `/` retaining the general frontend fallback. The frontend Nginx returns `index.html` for those routes. Backend `/admin/**` cutover is intentionally deferred until every required Gateway upstream exists.

The gateway defines an explicit `ACCOUNT_SERVICE_URI`. Order and settlement URIs are also set to their existing Kubernetes Services so the Gateway configuration is ready for later route-by-route cutover, but Ingress does not send those public paths to Gateway in this change. Routes whose service modules are not yet deployed remain outside this change.

## Kubernetes Resources

- `account-db-pv`, `account-db-pvc`, `account-db` StatefulSet, and headless `account-db-service`
  - Database: `lemuel_account`
  - Storage: retained `20Gi` hostPath, matching the settlement DB pattern
  - Credentials: `POSTGRES_USER` and `POSTGRES_PASSWORD` from `lemuel-secret`
- `account-config` ConfigMap
  - Production profile, ports `8080`, account JDBC URL, Redpanda bootstrap address, Kafka enabled, and shared JWT issuer/TTL
- `account-app` Deployment and `account-service` ClusterIP
  - Image: `ghcr.io/myoungsoo7/settlement-account:latest`
  - Startup, liveness, and readiness probes on `/actuator/health/*`
- `gateway-config` ConfigMap
  - `ORDER_SERVICE_URI=http://lemuel-service:8080`
  - `SETTLEMENT_SERVICE_URI=http://settlement-service:8080`
  - `ACCOUNT_SERVICE_URI=http://account-service:8080`
- `gateway-app` Deployment and `gateway-service` ClusterIP
  - Image: `ghcr.io/myoungsoo7/settlement-gateway:latest`
  - Health probes on `/actuator/health/*`

The existing `lemuel-secret` is reused. No plaintext credential or new sealed value is introduced.

The retained Account storage is a single-node hostPath choice: rescheduling the database Pod to another node does not move or replicate its data. Before multi-node production, replace it with a CSI-backed storage class and establish tested backup and restore procedures.

## CI and Delivery

The backend image matrix produces five images from the shared Dockerfile:

- `order-service` → `settlement`
- `settlement-service` → `settlement-settlement`
- `gateway-service` → `settlement-gateway`
- `account-service` → `settlement-account`
- `operation-service` → `settlement-operation`

The emergency image workflow uses the same matrix so recovery cannot silently omit the two new production units. ArgoCD's Application source enables recursive directory discovery because the manifests live under `k8s/base`, `k8s/ingress`, `k8s/security`, and `k8s/stroage`.

The current ArgoCD Application is a plain Directory app and has no supported Image Updater integration. The post-CI production smoke therefore checks only the revision currently deployed after a manifest reconciliation window; it does not prove that a newly built mutable tag was promoted. Subsequent image promotion requires a separate Kustomize-based immutable-tag mechanism. Git writeback and Image Updater are intentionally outside this change.

## Failure Handling and Rollout

- The account Deployment remains unready until Flyway, JPA validation, and the database health check succeed.
- The gateway remains addressable through a stable Service while Pods roll.
- Ingress is switched only to a named `gateway-service`; Kubernetes readiness prevents traffic from reaching an unready gateway Pod.
- Account data may initially be empty. That is a valid `200` response with zero aggregates and an empty balanced trial balance, not a deployment error.
- A missing account upstream results in a gateway error visible to smoke checks rather than being mistaken for a frontend asset problem.

## Verification

Repository-level verification will:

1. Parse all changed YAML files successfully.
2. Assert that normal and emergency CI matrices contain gateway and account modules with the expected image suffixes.
3. Assert that account DB, account app/service, gateway app/service, ConfigMaps, recursive ArgoCD discovery, the narrow `/api/account` Gateway rule, and explicit frontend SPA rules exist.
4. Run `:gateway-service:test` and `:account-service:test`.
5. Build both `:gateway-service:bootJar` and `:account-service:bootJar`.

Production verification after rollout must use HTTP and Kubernetes health/log evidence only. It must not connect directly to the production PostgreSQL database.

## Success Criteria

- Authenticated requests to all four `/api/account` dashboard endpoints return `200` in production.
- An empty account database renders zero/empty account dashboard data rather than a query error.
- Direct navigation and refresh of `/admin/ceo/accounts` returns the frontend SPA instead of `401`.
- Existing authentication, order, settlement, and general `/api` routes retain their current backend during the account-only Gateway cutover.
- CI builds and publishes both new images, including through the emergency workflow.
