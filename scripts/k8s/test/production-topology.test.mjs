import { existsSync, readFileSync } from "node:fs";
import assert from "node:assert/strict";
import test from "node:test";

const workflows = [
  {
    name: "normal CI",
    file: ".github/workflows/ci.yml",
  },
  {
    name: "emergency image build",
    file: ".github/workflows/backend-image-emergency.yml",
  },
];

const expectedImageMappings = [
  ["order-service", ""],
  ["settlement-service", "-settlement"],
  ["gateway-service", "-gateway"],
  ["account-service", "-account"],
];

const accountManifests = [
  "k8s/stroage/account-db-pv.yaml",
  "k8s/base/account-configmap.yaml",
  "k8s/base/account-deployment.yaml",
];

for (const workflow of workflows) {
  test(`${workflow.name} publishes every backend deployment image`, () => {
    assert.equal(existsSync(workflow.file), true, `${workflow.file} must exist`);
    const contents = readFileSync(workflow.file, "utf8");

    assert.match(contents, /^\s*REGISTRY:\s*ghcr\.io\s*$/m);
    assert.match(
      contents,
      /^\s*BACKEND_IMAGE:\s*\$\{\{\s*github\.repository\s*\}\}\s*$/m,
    );
    assert.match(
      contents,
      /uses:\s*docker\/metadata-action@v5[\s\S]*?images:\s*\$\{\{\s*env\.REGISTRY\s*\}\}\/\$\{\{\s*env\.BACKEND_IMAGE\s*\}\}\$\{\{\s*matrix\.image_suffix\s*\}\}/,
    );

    const configuredMappings = Array.from(
      contents.matchAll(
        /^\s*- module:\s*([^\s]+)\s*\r?\n\s*image_suffix:\s*"([^"]*)"\s*$/gm,
      ),
      ([, module, imageSuffix]) => [module, imageSuffix],
    );
    assert.deepEqual(configuredMappings, expectedImageMappings);
  });
}

test("production topology deploys the Account service and database", () => {
  for (const file of accountManifests) {
    assert.equal(existsSync(file), true, `${file} must exist`);
  }

  const accountDeployment = readFileSync(accountManifests[2], "utf8");
  const accountConfig = readFileSync(accountManifests[1], "utf8");

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
