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
