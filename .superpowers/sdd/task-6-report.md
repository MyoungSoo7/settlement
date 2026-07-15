# Task 6 Report: Deterministic Harness CI and Repository Hook

## Scope

- `.github/workflows/harness-guard.yml`
- `.claude/settings.json`
- `scripts/harness/test/audit.test.mjs`
- `scripts/harness/manifest.json`

## RED

Command:

```text
node --test scripts/harness/test/audit.test.mjs
```

Result: 9 passed, 3 failed.

- Workflow contract failed because the draft used `ACM`, an unchecked base, and a `HEAD~1` fallback, and omitted tracking and clean-diff steps.
- Settings contract failed because legacy plugin write and Bash hooks remained alongside the mandatory repository hook.
- Real audit execution failed because the governed workflow was not yet tracked.

Independent review then exposed a missing workflow test gate. A second focused RED run produced 11 passed and 1 failed because `Run harness tests` was absent from the required order.

## GREEN

Focused command:

```text
node --test scripts/harness/test/audit.test.mjs
```

Result: 12 passed, 0 failed.

Full command:

```text
node --test scripts/harness/test/*.test.mjs
```

Result: 54 passed, 0 failed.

Direct audit:

```text
node scripts/harness/harness-audit.mjs
```

Result: `harness-audit: healthy`.

Changed-file guard over the four implementation paths returned `harness guard: clean`.

## Audit

- Checkout uses full history and Node 22.
- Pull requests fetch the explicit base refspec and diff only from a validated merge base.
- Pushes validate the event's `before` commit; a zero SHA scans all tracked files.
- All diffs use `ACMR`; unsupported events fail closed and there is no heuristic fallback.
- Workflow order is harness tests, changed-file guard, real audit, manifest tracking, then `git diff --exit-code`.
- `.claude/settings.json` retains the mandatory repository-owned write guard and removes legacy installer paths.
- Manifest inventory includes the finalized workflow and settings paths.
- No `pwc` path was readied or committed by Task 6.

## Follow-up Findings

RED: the strengthened focused contract produced 11 passed and 1 failed. It rejected the short PR refspec and also required tests before changed-file computation plus strict validation of the push `before` value before zero-SHA handling or Git revision lookup.

GREEN: the workflow now force-fetches `+refs/heads/${BASE_REF}:refs/remotes/origin/${BASE_REF}`, rejects any `before` value that is not exactly 40 hexadecimal characters, and orders tests before changed-file computation. The focused suite passed 12/12 after the correction.

Independent follow-up review found the first negative assertion did not bind rejection to a failing exit. The contract test now checks representative empty, short, non-hex, and overlong values and mutation-tests that changing the validation block from `exit 1` to `exit 0` is rejected.
