# Task 7 Report: Fresh-repository proof

## RED

Command:

```text
node --test --test-name-pattern="fresh repository reproduces" scripts/harness/test/install.test.mjs
```

Result: exit 1. The new proof copied the repository snapshot unchanged and failed the explicit plugin-absence assertion (`true !== false`) because tracked `hackathon/settlement-copilot` and `hackathon/invest-copilot` trees leaked into the fixture. This demonstrated that the test fixture did not yet prove operation without optional plugin/MCP content.

## GREEN

The fixture now excludes only those two optional plugin roots. It initializes and commits its own repository, then runs all child commands with the child repository as `cwd`. The child test invocation sets `HARNESS_FRESH_CHILD=1` only to skip recursively creating another fresh repository; every copied test file still executes.

Focused command:

```text
node --test --test-name-pattern="fresh repository reproduces" scripts/harness/test/install.test.mjs
```

Result: exit 0, 1/1 passed. The child proof verifies installer attempts 1 and 2, all copied harness tests, guard self-test, harness audit, manifest tracking, and `git diff --exit-code`. It also verifies that a required file present but removed from the child index fails with `not tracked`, and that a staged deletion of a referenced harness script fails with `broken reference`.

## Final verification

```text
node --test scripts/harness/test/*.test.mjs
node scripts/harness/guard.mjs --self-test
node scripts/harness/harness-audit.mjs
```

Results: exit 0 throughout; 55/55 harness tests passed, 37/37 guard self-tests passed, and `harness-audit: healthy`. No audit or manifest behavior required modification.
