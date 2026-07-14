# Task 4 Report: Gateway deployment and public routing

## Scope

- Added `gateway-config` with only the three deployed upstream Services.
- Added `gateway-app` and `gateway-service` using the standard backend rollout, resources, probes, secret, and pull configuration.
- Routed only `/api/account` through Gateway and preserved `/api` on `lemuel-service`.
- Added explicit frontend routes for `/admin/ceo`, `/admin/system`, `/admin/operation`, `/admin/settlement`, and `/admin/login`.
- Enabled recursive plain-directory discovery for the Argo CD Application.
- Added no blanket `/admin` Gateway route and no Image Updater annotations.

## RED evidence

Command:

```text
node --test scripts/k8s/test/production-topology.test.mjs
```

Result: exit 1; 3 passed, 1 failed. The new topology test failed at the intended missing feature:

```text
k8s/base/gateway-configmap.yaml must exist
false !== true
```

## GREEN evidence

Commands:

```text
kubectl apply --dry-run=client -f k8s/base/gateway-configmap.yaml -o yaml
kubectl apply --dry-run=client -f k8s/base/gateway-deployment.yaml -o yaml
kubectl apply --dry-run=client -f k8s/ingress/ingress.yaml -o yaml
kubectl apply --dry-run=client -f k8s/argocd/argocd-app.yaml -o yaml --validate=false
node --test scripts/k8s/test/production-topology.test.mjs
```

Result: all commands exited 0. The focused topology suite reported 4 tests passed, 0 failed.

`git diff --check` also exited 0.

## Review

An independent read-only review found the manifests aligned with the brief but flagged that the initial path-to-backend regexes could match a backend from a later ingress entry. The test now scopes each assertion to one path block, covers all five required admin SPA paths, and explicitly rejects a blanket `/admin` path and Image Updater configuration. The complete validation set remained green after this correction.

## Constraint review

- `/api/account` targets `gateway-service:8080` and precedes `/api`.
- `/api` still targets `lemuel-service:8080`.
- All five requested admin SPA paths target `lemuel-frontend-service:80`.
- No blanket `/admin` path exists.
- Argo CD remains a plain `directory` source with `recurse: true`.
- No Image Updater annotations were introduced.
