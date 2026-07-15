import { existsSync, readFileSync } from "node:fs";
import assert from "node:assert/strict";
import test from "node:test";

const workflows = [
  {
    name: "normal CI",
    file: ".github/workflows/ci.yml",
    publishingJob: "backend-ghcr",
  },
  {
    name: "emergency image build",
    file: ".github/workflows/backend-image-emergency.yml",
    publishingJob: "build-push",
  },
];

const expectedImages = [
  ["order-service", ""],
  ["settlement-service", "-settlement"],
  ["gateway-service", "-gateway"],
  ["account-service", "-account"],
  ["operation-service", "-operation"],
];

const accountManifests = [
  "k8s/stroage/account-db-pv.yaml",
  "k8s/base/account-configmap.yaml",
  "k8s/base/account-deployment.yaml",
];

const gatewayManifests = [
  "k8s/base/gateway-configmap.yaml",
  "k8s/base/gateway-deployment.yaml",
];

const operationManifests = [
  "k8s/stroage/operation-db-pv.yaml",
  "k8s/base/operation-configmap.yaml",
  "k8s/base/operation-deployment.yaml",
];

function ingressPathBlock(contents, path) {
  const lines = contents.split(/\r?\n/);
  const start = lines.findIndex((line) => line.trim() === `- path: ${path}`);
  assert.notEqual(start, -1, `${path} ingress path must exist`);

  const nextPathOffset = lines
    .slice(start + 1)
    .findIndex((line) => line.trim().startsWith("- path:"));
  const end = nextPathOffset === -1 ? lines.length : start + 1 + nextPathOffset;
  return lines.slice(start, end).join("\n");
}

function workflowJobBlock(contents, jobName) {
  const lines = contents.split(/\r?\n/);
  const start = lines.findIndex((line) => line === `  ${jobName}:`);
  assert.notEqual(start, -1, `${jobName} workflow job must exist`);

  const nextJobOffset = lines
    .slice(start + 1)
    .findIndex((line) => /^  [a-zA-Z0-9_-]+:\s*$/.test(line));
  const end = nextJobOffset === -1 ? lines.length : start + 1 + nextJobOffset;
  return lines.slice(start, end).join("\n");
}

function manifestResource(contents, kind, name) {
  const resource = contents.split(/^\s*---\s*$/m).find((document) => {
    const resourceKind = document.match(/^kind:\s*(\S+)\s*$/m)?.[1];
    const resourceName = document.match(
      /^metadata:\s*\r?\n(?:^[ \t].*\r?\n)*?^\s{2}name:\s*([^\s#]+)\s*$/m,
    )?.[1];
    return resourceKind === kind && resourceName === name;
  });

  assert.ok(resource, `${kind}/${name} must exist`);
  return resource;
}

function manifestContainer(resource, name) {
  const lines = resource.split(/\r?\n/);
  const start = lines.findIndex((line) => line === `        - name: ${name}`);
  assert.notEqual(start, -1, `container/${name} must exist`);

  const nextBlockOffset = lines
    .slice(start + 1)
    .findIndex((line) => /^(?: {0,6}\S| {8}- name:)/.test(line));
  const end = nextBlockOffset === -1 ? lines.length : start + 1 + nextBlockOffset;
  return lines.slice(start, end).join("\n");
}

for (const workflow of workflows) {
  test(`${workflow.name} publishes every backend deployment image`, () => {
    assert.equal(existsSync(workflow.file), true, `${workflow.file} must exist`);
    const contents = readFileSync(workflow.file, "utf8");
    const publishingJob = workflowJobBlock(contents, workflow.publishingJob);

    assert.match(contents, /^\s*REGISTRY:\s*ghcr\.io\s*$/m);
    assert.match(
      contents,
      /^\s*BACKEND_IMAGE:\s*\$\{\{\s*github\.repository\s*\}\}\s*$/m,
    );
    assert.match(
      publishingJob,
      /uses:\s*docker\/metadata-action@v5[\s\S]*?images:\s*\$\{\{\s*env\.REGISTRY\s*\}\}\/\$\{\{\s*env\.BACKEND_IMAGE\s*\}\}\$\{\{\s*matrix\.image_suffix\s*\}\}/,
    );

    const configuredMappings = Array.from(
      publishingJob.matchAll(
        /^\s*- module:\s*([^\s]+)\s*\r?\n\s*image_suffix:\s*"([^"]*)"\s*$/gm,
      ),
      ([, module, imageSuffix]) => [module, imageSuffix],
    );
    assert.deepEqual(configuredMappings, expectedImages);

    assert.match(publishingJob, /^\s*push:\s*true\s*$/m);
    assert.match(
      publishingJob,
      /^\s*MODULE=\$\{\{\s*matrix\.module\s*\}\}\s*$/m,
    );
  });
}

test("normal pull request CI runs the production topology test with an explicit Node version", () => {
  const contents = readFileSync(".github/workflows/ci.yml", "utf8");
  const backendCi = workflowJobBlock(contents, "backend-ci");

  assert.match(contents, /^\s*pull_request:\s*$/m);
  assert.match(
    backendCi,
    /^\s*if:\s*needs\.changes\.outputs\.backend == 'true'\s*$/m,
  );
  assert.match(backendCi, /uses:\s*actions\/setup-node@v4/);
  assert.match(backendCi, /^\s*node-version:\s*["']20["']\s*$/m);
  assert.match(
    backendCi,
    /^\s*run:\s*node --test scripts\/k8s\/test\/production-topology\.test\.mjs\s*$/m,
  );
});

test("production smoke describes the deployed revision without claiming image promotion", () => {
  const contents = readFileSync(".github/workflows/ci.yml", "utf8");
  const smokeJob = workflowJobBlock(contents, "production-revision-smoke");
  const design = readFileSync(
    "docs/superpowers/specs/2026-07-15-gateway-account-production-deployment-design.md",
    "utf8",
  );

  assert.match(smokeJob, /name:\s*Currently deployed production revision smoke/i);
  assert.match(smokeJob, /Wait for ArgoCD manifest reconciliation/);
  assert.match(smokeJob, /^\s*run:\s*sleep 180\s*$/m);
  assert.doesNotMatch(smokeJob, /image[- ]updater|rollout/i);
  assert.match(design, /Kustomize/i);
  assert.match(design, /immutable[- ]tag/i);
});

test("production topology deploys the Account service and database", () => {
  for (const file of accountManifests) {
    assert.equal(existsSync(file), true, `${file} must exist`);
  }

  const accountDeployment = readFileSync(accountManifests[2], "utf8");
  const accountConfig = readFileSync(accountManifests[1], "utf8");
  const accountDatabase = readFileSync(accountManifests[0], "utf8");

  manifestResource(accountDatabase, "PersistentVolume", "account-db-pv");
  manifestResource(accountDatabase, "PersistentVolumeClaim", "account-db-pvc");
  manifestResource(accountDatabase, "StatefulSet", "account-db");
  manifestResource(accountDatabase, "Service", "account-db-service");
  manifestResource(accountDeployment, "Deployment", "account-app");
  manifestResource(accountDeployment, "Service", "account-service");

  assert.match(
    accountDeployment,
    /image:\s*ghcr\.io\/myoungsoo7\/settlement-account:latest/,
  );
  assert.match(accountConfig, /MANAGEMENT_SERVER_PORT:\s*"8080"/);
  assert.match(
    accountConfig,
    /jdbc:postgresql:\/\/account-db-service:5432\/lemuel_account\?reWriteBatchedInserts=true/,
  );
  assert.match(
    accountConfig,
    /SPRING_KAFKA_BOOTSTRAP_SERVERS:\s*"redpanda:29092"/,
  );
});

test("production topology deploys Gateway and preserves narrow public routing", () => {
  for (const file of gatewayManifests) {
    assert.equal(existsSync(file), true, `${file} must exist`);
  }

  const gatewayConfig = readFileSync(gatewayManifests[0], "utf8");
  const gatewayDeployment = readFileSync(gatewayManifests[1], "utf8");
  const ingress = readFileSync("k8s/ingress/ingress.yaml", "utf8");
  const argocd = readFileSync("k8s/argocd/argocd-app.yaml", "utf8");
  const gatewayApp = manifestResource(
    gatewayDeployment,
    "Deployment",
    "gateway-app",
  );

  manifestResource(gatewayDeployment, "Service", "gateway-service");
  assert.doesNotMatch(gatewayApp, /\bsecretRef\s*:/);

  assert.match(
    gatewayDeployment,
    /image:\s*ghcr\.io\/myoungsoo7\/settlement-gateway:latest/,
  );
  assert.match(
    gatewayConfig,
    /ACCOUNT_SERVICE_URI:\s*"http:\/\/account-service:8080"/,
  );
  assert.match(argocd, /directory:\s*\n\s+recurse: true/);
  assert.match(ingressPathBlock(ingress, "/api/account"), /name: gateway-service/);
  assert.match(ingressPathBlock(ingress, "/api"), /name: lemuel-service/);

  for (const path of [
    "/admin/ceo",
    "/admin/system",
    "/admin/operation",
    "/admin/settlement",
    "/admin/login",
  ]) {
    assert.match(
      ingressPathBlock(ingress, path),
      /name: lemuel-frontend-service/,
    );
  }

  assert.equal(
    ingress.split(/\r?\n/).some((line) => line.trim() === "- path: /admin"),
    false,
    "blanket /admin ingress path must not exist",
  );
  assert.doesNotMatch(argocd, /(?:argocd-)?image-updater/i);
});

test("production topology deploys the Operation service and database", () => {
  for (const file of operationManifests) {
    assert.equal(existsSync(file), true, `${file} must exist`);
  }

  const operationDatabase = readFileSync(operationManifests[0], "utf8");
  const operationConfig = readFileSync(operationManifests[1], "utf8");
  const operationDeployment = readFileSync(operationManifests[2], "utf8");

  const operationPv = manifestResource(
    operationDatabase,
    "PersistentVolume",
    "operation-db-pv",
  );
  const operationPvc = manifestResource(
    operationDatabase,
    "PersistentVolumeClaim",
    "operation-db-pvc",
  );
  const operationStatefulSet = manifestResource(
    operationDatabase,
    "StatefulSet",
    "operation-db",
  );
  const operationDbService = manifestResource(
    operationDatabase,
    "Service",
    "operation-db-service",
  );
  const operationConfigMap = manifestResource(
    operationConfig,
    "ConfigMap",
    "operation-config",
  );
  const operationApp = manifestResource(
    operationDeployment,
    "Deployment",
    "operation-app",
  );
  const operationService = manifestResource(
    operationDeployment,
    "Service",
    "operation-service",
  );
  const operationContainer = manifestContainer(operationApp, "operation");

  for (const resource of [
    operationPvc,
    operationStatefulSet,
    operationDbService,
    operationConfigMap,
    operationApp,
    operationService,
  ]) {
    assert.match(resource, /^\s{2}namespace:\s*lemuel\s*$/m);
  }

  assert.match(
    operationContainer,
    /image:\s*ghcr\.io\/myoungsoo7\/settlement-operation:latest/,
  );
  assert.match(
    operationConfigMap,
    /SPRING_DATASOURCE_URL:\s*"jdbc:postgresql:\/\/operation-db-service:5432\/lemuel_operation\?reWriteBatchedInserts=true"/,
  );
  assert.match(
    operationConfigMap,
    /APP_KAFKA_ENABLED:\s*"true"/,
  );
  assert.match(
    operationConfigMap,
    /SPRING_KAFKA_BOOTSTRAP_SERVERS:\s*"redpanda:29092"/,
  );
  assert.match(operationConfigMap, /SERVER_PORT:\s*"8080"/);
  assert.match(operationConfigMap, /MANAGEMENT_SERVER_PORT:\s*"8080"/);
  assert.match(operationConfigMap, /JWT_ISSUER:\s*"lemuel-service"/);
  assert.match(operationConfigMap, /OPS_PROMETHEUS_ENABLED:\s*"false"/);
  assert.match(operationConfigMap, /OPS_ANOMALY_ENABLED:\s*"false"/);
  assert.match(
    operationConfigMap,
    /APP_SECURITY_INTERNAL_KEY_REQUIRED:\s*"true"/,
  );

  assert.match(operationContainer, /containerPort:\s*8080/);
  assert.match(
    operationContainer,
    /envFrom:\s*\r?\n\s*- configMapRef:\s*\r?\n\s*name:\s*operation-config\s*\r?\n\s*- secretRef:\s*\r?\n\s*name:\s*lemuel-secret/,
  );
  assert.match(
    operationApp,
    /selector:\s*\r?\n\s{4}matchLabels:\s*\r?\n\s{6}app:\s*operation\s*\r?\n\s{6}tier:\s*backend\s*\r?\n\s{2}template:/,
  );
  assert.match(
    operationApp,
    /template:\s*\r?\n\s{4}metadata:\s*\r?\n\s{6}labels:\s*\r?\n\s{8}app:\s*operation\s*\r?\n\s{8}tier:\s*backend\s*\r?\n\s{4}spec:/,
  );
  assert.match(
    operationService,
    /selector:\s*\r?\n\s{4}app:\s*operation\s*\r?\n\s{4}tier:\s*backend\s*\r?\n\s{2}ports:/,
  );
  assert.match(
    operationService,
    /ports:\s*\r?\n\s*- name:\s*http\s*\r?\n\s*protocol:\s*TCP\s*\r?\n\s*port:\s*8080\s*\r?\n\s*targetPort:\s*8080/,
  );
  assert.match(
    operationContainer,
    /startupProbe:\s*\r?\n\s*httpGet:\s*\r?\n\s*path:\s*\/actuator\/health\/liveness\s*\r?\n\s*port:\s*8080/,
  );
  assert.match(
    operationContainer,
    /livenessProbe:\s*\r?\n\s*httpGet:\s*\r?\n\s*path:\s*\/actuator\/health\/liveness\s*\r?\n\s*port:\s*8080/,
  );
  assert.match(
    operationContainer,
    /readinessProbe:\s*\r?\n\s*httpGet:\s*\r?\n\s*path:\s*\/actuator\/health\/readiness\s*\r?\n\s*port:\s*8080/,
  );

  assert.match(operationStatefulSet, /serviceName:\s*operation-db-service/);
  assert.match(
    operationStatefulSet,
    /selector:\s*\r?\n\s{4}matchLabels:\s*\r?\n\s{6}app:\s*operation-db\s*\r?\n\s{2}template:/,
  );
  assert.match(
    operationStatefulSet,
    /template:\s*\r?\n\s{4}metadata:\s*\r?\n\s{6}labels:\s*\r?\n\s{8}app:\s*operation-db\s*\r?\n\s{4}spec:/,
  );
  assert.match(
    operationStatefulSet,
    /persistentVolumeClaim:\s*\r?\n\s*claimName:\s*operation-db-pvc/,
  );
  assert.match(
    operationStatefulSet,
    /name:\s*POSTGRES_DB\s*\r?\n\s*value:\s*lemuel_operation/,
  );
  assert.match(
    operationStatefulSet,
    /^\s{12}- name:\s*POSTGRES_USER\s*\r?\n\s{14}valueFrom:\s*\r?\n\s{16}secretKeyRef:\s*\r?\n\s{18}name:\s*lemuel-secret\s*\r?\n\s{18}key:\s*POSTGRES_USER\s*$/m,
  );
  assert.match(
    operationStatefulSet,
    /^\s{12}- name:\s*POSTGRES_PASSWORD\s*\r?\n\s{14}valueFrom:\s*\r?\n\s{16}secretKeyRef:\s*\r?\n\s{18}name:\s*lemuel-secret\s*\r?\n\s{18}key:\s*POSTGRES_PASSWORD\s*$/m,
  );
  assert.match(
    operationStatefulSet,
    /livenessProbe:\s*\r?\n\s*exec:\s*\r?\n\s*command:\s*\[[^\r\n]*pg_isready/,
  );
  assert.match(
    operationStatefulSet,
    /readinessProbe:\s*\r?\n\s*exec:\s*\r?\n\s*command:\s*\[[^\r\n]*pg_isready/,
  );

  assert.match(operationDbService, /clusterIP:\s*None/);
  assert.match(
    operationDbService,
    /selector:\s*\r?\n\s{4}app:\s*operation-db\s*\r?\n\s{2}ports:/,
  );
  assert.match(
    operationDbService,
    /ports:\s*\r?\n\s*- name:\s*postgres\s*\r?\n\s*port:\s*5432\s*\r?\n\s*targetPort:\s*5432/,
  );
  assert.match(operationPvc, /matchLabels:\s*\r?\n\s*app:\s*operation-db/);
  assert.match(operationPv, /^\s{4}app:\s*operation-db\s*$/m);
  assert.match(
    operationPv,
    /Single-node only:[^\r\n]*hostPath[^\r\n]*one node/i,
  );
  assert.match(operationPv, /path:\s*\/data\/k8s\/operation-db/);
});
