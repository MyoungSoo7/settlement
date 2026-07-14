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

const imageMappings = [
  ["order-service", "", "ghcr.io/myoungsoo7/settlement"],
  ["settlement-service", "-settlement", "ghcr.io/myoungsoo7/settlement-settlement"],
  ["gateway-service", "-gateway", "ghcr.io/myoungsoo7/settlement-gateway"],
  ["account-service", "-account", "ghcr.io/myoungsoo7/settlement-account"],
];

for (const workflow of workflows) {
  test(`${workflow.name} publishes every backend deployment image`, () => {
    assert.equal(existsSync(workflow.file), true, `${workflow.file} must exist`);
    const contents = readFileSync(workflow.file, "utf8");

    for (const [module, imageSuffix] of imageMappings) {
      assert.match(
        contents,
        new RegExp(`module: ${module}[\\s\\S]*?image_suffix: "${imageSuffix}"`),
      );
    }

    for (const [module, , image] of imageMappings) {
      assert.match(contents, new RegExp(`${module}[^\\n]*${image}`));
    }
  });
}
