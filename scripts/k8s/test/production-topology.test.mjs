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

function manifestTopLevelBlock(resource, key) {
  const lines = resource.split(/\r?\n/);
  const start = lines.findIndex((line) => line === `${key}:`);
  assert.notEqual(start, -1, `${key} block must exist`);

  const nextTopLevelOffset = lines
    .slice(start + 1)
    .findIndex((line) => /^\S/.test(line));
  const end =
    nextTopLevelOffset === -1
      ? lines.length
      : start + 1 + nextTopLevelOffset;
  return lines.slice(start, end).join("\n");
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

function accountTopologyInputs() {
  return {
    accountDatabase: readFileSync(accountManifests[0], "utf8"),
    accountConfig: readFileSync(accountManifests[1], "utf8"),
    accountDeployment: readFileSync(accountManifests[2], "utf8"),
  };
}

function gatewayTopologyInputs() {
  return {
    gatewayConfig: readFileSync(gatewayManifests[0], "utf8"),
    gatewayDeployment: readFileSync(gatewayManifests[1], "utf8"),
  };
}

function assertNamespace(resource, description) {
  assert.match(
    resource,
    /^\s{2}namespace:\s*lemuel\s*$/m,
    `${description} namespace must be lemuel`,
  );
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function assertAppDeploymentTopology({
  deployment,
  service,
  app,
  containerName,
  image,
  configMap,
  secret,
}) {
  const container = manifestContainer(deployment, containerName);

  assertNamespace(deployment, `${app} Deployment`);
  assertNamespace(service, `${app} Service`);
  assert.match(deployment, /^\s{2}replicas:\s*1\s*$/m, `${app} must run one replica`);
  assert.match(
    deployment,
    /strategy:\s*\r?\n\s{4}type:\s*RollingUpdate\s*\r?\n\s{4}rollingUpdate:\s*\r?\n\s{6}maxSurge:\s*1\s*\r?\n\s{6}maxUnavailable:\s*0/,
    `${app} must preserve its zero-downtime rolling strategy`,
  );
  assert.match(
    deployment,
    new RegExp(
      `selector:\\s*\\r?\\n\\s{4}matchLabels:\\s*\\r?\\n\\s{6}app:\\s*${app}\\s*\\r?\\n\\s{6}tier:\\s*backend\\s*\\r?\\n\\s{2}template:`,
    ),
    `${app} Deployment selector must match app/tier labels`,
  );
  assert.match(
    deployment,
    new RegExp(
      `template:\\s*\\r?\\n\\s{4}metadata:\\s*\\r?\\n\\s{6}labels:\\s*\\r?\\n\\s{8}app:\\s*${app}\\s*\\r?\\n\\s{8}tier:\\s*backend\\s*\\r?\\n\\s{4}spec:`,
    ),
    `${app} Pod labels must match the Deployment selector`,
  );
  assert.match(
    service,
    new RegExp(
      `selector:\\s*\\r?\\n\\s{4}app:\\s*${app}\\s*\\r?\\n\\s{4}tier:\\s*backend\\s*\\r?\\n\\s{2}ports:`,
    ),
    `${app} Service selector must match the Pod labels`,
  );
  assert.match(
    container,
    new RegExp(`^\\s*image:\\s*${escapeRegExp(image)}\\s*$`, "m"),
    `${app} image must be ${image}`,
  );
  assert.match(container, /^\s*imagePullPolicy:\s*Always\s*$/m);
  assert.match(
    container,
    /ports:\s*\r?\n\s*- name:\s*http\s*\r?\n\s*containerPort:\s*8080\s*\r?\n\s*protocol:\s*TCP/,
    `${app} container must expose TCP port 8080`,
  );
  assert.match(
    service,
    /ports:\s*\r?\n\s*- name:\s*http\s*\r?\n\s*protocol:\s*TCP\s*\r?\n\s*port:\s*8080\s*\r?\n\s*targetPort:\s*8080/,
    `${app} Service must target TCP port 8080`,
  );
  assert.match(service, /^\s{2}type:\s*ClusterIP\s*$/m);
  assert.match(
    container,
    new RegExp(
      `envFrom:\\s*\\r?\\n\\s*- configMapRef:\\s*\\r?\\n\\s*name:\\s*${configMap}`,
    ),
    `${app} must import ${configMap}`,
  );
  if (secret) {
    assert.match(
      container,
      new RegExp(
        `configMapRef:\\s*\\r?\\n\\s*name:\\s*${configMap}\\s*\\r?\\n\\s*- secretRef:\\s*\\r?\\n\\s*name:\\s*${secret}`,
      ),
      `${app} must import ${secret} after its ConfigMap`,
    );
  } else {
    assert.doesNotMatch(container, /\bsecretRef\s*:/, `${app} must not import a Secret`);
  }
  assert.match(
    container,
    /resources:\s*\r?\n\s*requests:\s*\r?\n\s*memory:\s*"512Mi"\s*\r?\n\s*cpu:\s*"250m"\s*\r?\n\s*limits:\s*\r?\n\s*memory:\s*"2Gi"\s*\r?\n\s*cpu:\s*"1000m"/,
    `${app} resources must preserve production requests and limits`,
  );
  for (const [probe, path] of [
    ["startupProbe", "/actuator/health/liveness"],
    ["livenessProbe", "/actuator/health/liveness"],
    ["readinessProbe", "/actuator/health/readiness"],
  ]) {
    assert.match(
      container,
      new RegExp(
        `${probe}:\\s*\\r?\\n\\s*httpGet:\\s*\\r?\\n\\s*path:\\s*${escapeRegExp(path)}\\s*\\r?\\n\\s*port:\\s*8080`,
      ),
      `${app} ${probe} must use ${path}:8080`,
    );
  }
  assert.match(
    deployment,
    /imagePullSecrets:\s*\r?\n\s*- name:\s*ghcr-secret/,
    `${app} must use ghcr-secret for image pulls`,
  );
}

function assertAccountTopology({
  accountDatabase,
  accountConfig,
  accountDeployment,
}) {
  const pv = manifestResource(accountDatabase, "PersistentVolume", "account-db-pv");
  const pvc = manifestResource(
    accountDatabase,
    "PersistentVolumeClaim",
    "account-db-pvc",
  );
  const statefulSet = manifestResource(
    accountDatabase,
    "StatefulSet",
    "account-db",
  );
  const dbService = manifestResource(
    accountDatabase,
    "Service",
    "account-db-service",
  );
  const configMap = manifestResource(accountConfig, "ConfigMap", "account-config");
  const deployment = manifestResource(
    accountDeployment,
    "Deployment",
    "account-app",
  );
  const service = manifestResource(
    accountDeployment,
    "Service",
    "account-service",
  );
  const data = manifestTopLevelBlock(configMap, "data");
  const dbContainer = manifestContainer(statefulSet, "account-db");

  for (const [resource, description] of [
    [pvc, "Account PVC"],
    [statefulSet, "Account StatefulSet"],
    [dbService, "Account DB Service"],
    [configMap, "Account ConfigMap"],
  ]) {
    assertNamespace(resource, description);
  }

  assert.match(data, /^  SPRING_PROFILES_ACTIVE:\s*"production"\s*$/m);
  assert.match(data, /^  SERVER_PORT:\s*"8080"\s*$/m);
  assert.match(data, /^  MANAGEMENT_SERVER_PORT:\s*"8080"\s*$/m);
  assert.match(
    data,
    /^  SPRING_DATASOURCE_URL:\s*"jdbc:postgresql:\/\/account-db-service:5432\/lemuel_account\?reWriteBatchedInserts=true"\s*$/m,
  );
  assert.match(data, /^  APP_KAFKA_ENABLED:\s*"true"\s*$/m);
  assert.match(
    data,
    /^  SPRING_KAFKA_BOOTSTRAP_SERVERS:\s*"redpanda:29092"\s*$/m,
  );
  assert.match(data, /^  JWT_ISSUER:\s*"lemuel-service"\s*$/m);
  assert.match(data, /^  JWT_TTL_SECONDS:\s*"86400"\s*$/m);
  assertAppDeploymentTopology({
    deployment,
    service,
    app: "account",
    containerName: "account",
    image: "ghcr.io/myoungsoo7/settlement-account:latest",
    configMap: "account-config",
    secret: "lemuel-secret",
  });

  assert.match(statefulSet, /^\s{2}serviceName:\s*account-db-service\s*$/m);
  assert.match(
    statefulSet,
    /selector:\s*\r?\n\s{4}matchLabels:\s*\r?\n\s{6}app:\s*account-db\s*\r?\n\s{2}template:/,
    "Account StatefulSet selector must match its Pod labels",
  );
  assert.match(
    statefulSet,
    /template:\s*\r?\n\s{4}metadata:\s*\r?\n\s{6}labels:\s*\r?\n\s{8}app:\s*account-db\s*\r?\n\s{4}spec:/,
    "Account database Pod labels must match the StatefulSet selector",
  );
  assert.match(
    statefulSet,
    /volumes:\s*\r?\n\s*- name:\s*account-db-storage\s*\r?\n\s*persistentVolumeClaim:\s*\r?\n\s*claimName:\s*account-db-pvc/,
    "Account StatefulSet must mount account-db-pvc",
  );
  assert.match(
    dbContainer,
    /name:\s*POSTGRES_DB\s*\r?\n\s*value:\s*lemuel_account/,
  );
  assert.match(dbContainer, /^\s*image:\s*postgres:17-alpine\s*$/m);
  assert.match(
    dbContainer,
    /ports:\s*\r?\n\s*- name:\s*postgres\s*\r?\n\s*containerPort:\s*5432/,
  );
  assert.match(
    dbContainer,
    /name:\s*PGDATA\s*\r?\n\s*value:\s*\/var\/lib\/postgresql\/data\/pgdata/,
  );
  assert.match(
    dbContainer,
    /volumeMounts:\s*\r?\n\s*- name:\s*account-db-storage\s*\r?\n\s*mountPath:\s*\/var\/lib\/postgresql\/data/,
    "Account DB container must mount account-db-storage at the PostgreSQL data path",
  );
  for (const key of ["POSTGRES_USER", "POSTGRES_PASSWORD"]) {
    assert.match(
      dbContainer,
      new RegExp(
        `- name:\\s*${key}\\s*\\r?\\n\\s*valueFrom:\\s*\\r?\\n\\s*secretKeyRef:\\s*\\r?\\n\\s*name:\\s*lemuel-secret\\s*\\r?\\n\\s*key:\\s*${key}`,
      ),
      `${key} must use the matching lemuel-secret key`,
    );
  }
  for (const probe of ["livenessProbe", "readinessProbe"]) {
    assert.match(
      dbContainer,
      new RegExp(
        `${probe}:\\s*\\r?\\n\\s*exec:\\s*\\r?\\n\\s*command:\\s*\\[[^\\r\\n]*pg_isready`,
      ),
      `Account DB ${probe} must run pg_isready`,
    );
  }
  assert.match(
    dbContainer,
    /resources:\s*\r?\n\s*requests:\s*\r?\n\s*memory:\s*"256Mi"\s*\r?\n\s*cpu:\s*"250m"\s*\r?\n\s*limits:\s*\r?\n\s*memory:\s*"1Gi"\s*\r?\n\s*cpu:\s*"1000m"/,
    "Account DB resources must preserve production requests and limits",
  );
  assert.match(dbService, /^\s{2}clusterIP:\s*None\s*$/m);
  assert.match(
    dbService,
    /selector:\s*\r?\n\s{4}app:\s*account-db\s*\r?\n\s{2}ports:/,
    "Account DB Service selector must match the StatefulSet Pod labels",
  );
  assert.match(
    dbService,
    /ports:\s*\r?\n\s*- name:\s*postgres\s*\r?\n\s*port:\s*5432\s*\r?\n\s*targetPort:\s*5432\s*\r?\n\s*protocol:\s*TCP/,
  );
  assert.match(pv, /^\s{4}app:\s*account-db\s*$/m);
  assert.match(pvc, /matchLabels:\s*\r?\n\s*app:\s*account-db/);
  assert.match(pv, /accessModes:\s*\r?\n\s*- ReadWriteOnce/);
  assert.match(pvc, /accessModes:\s*\r?\n\s*- ReadWriteOnce/);
  assert.match(pv, /^\s{2}storageClassName:\s*manual\s*$/m);
  assert.match(pvc, /^\s{2}storageClassName:\s*manual\s*$/m);
  assert.match(pv, /persistentVolumeReclaimPolicy:\s*Retain/);
  assert.match(pv, /capacity:\s*\r?\n\s*storage:\s*20Gi/);
  assert.match(pvc, /requests:\s*\r?\n\s*storage:\s*20Gi/);
  assert.match(pv, /path:\s*\/data\/k8s\/account-db/);
  assert.match(pv, /type:\s*DirectoryOrCreate/);
  assert.match(
    pv,
    /Single-node only:[^\r\n]*hostPath[^\r\n]*one node/i,
    "Account hostPath PV must warn that it is single-node only",
  );
}

function assertGatewayTopology({ gatewayConfig, gatewayDeployment }) {
  const configMap = manifestResource(gatewayConfig, "ConfigMap", "gateway-config");
  const deployment = manifestResource(
    gatewayDeployment,
    "Deployment",
    "gateway-app",
  );
  const service = manifestResource(
    gatewayDeployment,
    "Service",
    "gateway-service",
  );
  const data = manifestTopLevelBlock(configMap, "data");

  assertNamespace(configMap, "Gateway ConfigMap");
  assert.match(data, /^  SPRING_PROFILES_ACTIVE:\s*"production"\s*$/m);
  assert.match(data, /^  SERVER_PORT:\s*"8080"\s*$/m);
  for (const [key, uri] of [
    ["ORDER_SERVICE_URI", "http://lemuel-service:8080"],
    ["SETTLEMENT_SERVICE_URI", "http://settlement-service:8080"],
    ["ACCOUNT_SERVICE_URI", "http://account-service:8080"],
    ["OPERATION_SERVICE_URI", "http://operation-service:8080"],
  ]) {
    assert.match(
      data,
      new RegExp(`^  ${key}:\\s*"${escapeRegExp(uri)}"\\s*$`, "m"),
      `${key} must target ${uri}`,
    );
  }
  assertAppDeploymentTopology({
    deployment,
    service,
    app: "gateway",
    containerName: "gateway",
    image: "ghcr.io/myoungsoo7/settlement-gateway:latest",
    configMap: "gateway-config",
  });
}

test("Account topology assertions reject cross-wired credentials and Service selectors", () => {
  const inputs = accountTopologyInputs();

  assert.throws(
    () =>
      assertAccountTopology({
        ...inputs,
        accountDatabase: inputs.accountDatabase.replace(
          "key: POSTGRES_USER",
          "key: WRONG_POSTGRES_USER",
        ),
      }),
    /POSTGRES_USER/,
  );
  assert.throws(
    () =>
      assertAccountTopology({
        ...inputs,
        accountDeployment: inputs.accountDeployment.replace(
          "    tier: backend\n  ports:",
          "    tier: frontend\n  ports:",
        ),
      }),
    /Service selector/,
  );
  assert.throws(
    () =>
      assertAccountTopology({
        ...inputs,
        accountDatabase: inputs.accountDatabase.replace(
          "mountPath: /var/lib/postgresql/data",
          "mountPath: /wrong/account-data",
        ),
      }),
    /must mount account-db-storage/,
  );
  assert.throws(
    () =>
      assertAccountTopology({
        ...inputs,
        accountConfig: inputs.accountConfig.replace(
          'APP_KAFKA_ENABLED: "true"',
          'APP_KAFKA_ENABLED: "false"',
        ),
      }),
    /APP_KAFKA_ENABLED/,
  );
});

test("Gateway topology assertions reject wrong upstreams and secret exposure", () => {
  const inputs = gatewayTopologyInputs();

  assert.throws(
    () =>
      assertGatewayTopology({
        ...inputs,
        gatewayConfig: inputs.gatewayConfig.replace(
          "http://operation-service:8080",
          "http://wrong-operation-service:8080",
        ),
      }),
    /OPERATION_SERVICE_URI/,
  );
  assert.throws(
    () =>
      assertGatewayTopology({
        ...inputs,
        gatewayDeployment: inputs.gatewayDeployment.replace(
          "            - configMapRef:\n                name: gateway-config",
          "            - configMapRef:\n                name: gateway-config\n            - secretRef:\n                name: lemuel-secret",
        ),
      }),
    /must not import a Secret/,
  );
  assert.throws(
    () =>
      assertGatewayTopology({
        ...inputs,
        gatewayDeployment: inputs.gatewayDeployment.replace(
          "path: /actuator/health/readiness",
          "path: /actuator/health/liveness",
        ),
      }),
    /readinessProbe/,
  );
});

test("Ingress documentation orders narrow Gateway routes before general API routing", () => {
  const ingress = readFileSync("k8s/ingress/ingress.yaml", "utf8");
  const header = ingress.split("apiVersion:", 1)[0];

  assert.match(
    header,
    /\/api\/account, \/api\/ops[^\r\n]*gateway-service[\s\S]*\/api,[^\r\n]*lemuel-service/,
  );
});

test("Account storage design gates multi-node production on durable storage and recovery", () => {
  const design = readFileSync(
    "docs/superpowers/specs/2026-07-15-gateway-account-production-deployment-design.md",
    "utf8",
  );

  assert.match(design, /single-node[^\r\n]*hostPath/i);
  assert.match(design, /multi-node production[^\r\n]*(?:CSI|storage class)/i);
  assert.match(design, /backup[^\r\n]*(?:and|\/)[^\r\n]*restore/i);
});

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
  assertAccountTopology(accountTopologyInputs());
});

test("production topology deploys Gateway and preserves narrow public routing", () => {
  for (const file of gatewayManifests) {
    assert.equal(existsSync(file), true, `${file} must exist`);
  }

  const ingress = readFileSync("k8s/ingress/ingress.yaml", "utf8");
  const argocd = readFileSync("k8s/argocd/argocd-app.yaml", "utf8");

  assertGatewayTopology(gatewayTopologyInputs());
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

test("production topology routes the Operation API through Gateway", () => {
  const gatewayConfig = readFileSync(gatewayManifests[0], "utf8");
  const ingress = readFileSync("k8s/ingress/ingress.yaml", "utf8");
  const gatewayConfigMap = manifestResource(
    gatewayConfig,
    "ConfigMap",
    "gateway-config",
  );
  const gatewayData = manifestTopLevelBlock(gatewayConfigMap, "data");
  const accountPath = ingressPathBlock(ingress, "/api/account");
  const operationPath = ingressPathBlock(ingress, "/api/ops");
  const generalApiPath = ingressPathBlock(ingress, "/api");
  const ingressLines = ingress.split(/\r?\n/);

  assert.match(
    gatewayData,
    /^  OPERATION_SERVICE_URI:\s*"http:\/\/operation-service:8080"\s*$/m,
  );
  assert.match(
    accountPath,
    /name:\s*gateway-service[\s\S]*?number:\s*8080/,
  );
  assert.match(
    operationPath,
    /name:\s*gateway-service[\s\S]*?number:\s*8080/,
  );
  assert.match(operationPath, /^\s*pathType:\s*Prefix\s*$/m);
  assert.match(
    generalApiPath,
    /name:\s*lemuel-service[\s\S]*?number:\s*8080/,
  );
  assert.ok(
    ingressLines.findIndex((line) => line.trim() === "- path: /api/ops") <
      ingressLines.findIndex((line) => line.trim() === "- path: /api"),
    "/api/ops must appear before the general /api path",
  );
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
