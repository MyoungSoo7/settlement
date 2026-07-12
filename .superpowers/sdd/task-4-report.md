# Task 4 Report

## RED

- Command: `node --test scripts/harness/test/install.test.mjs`
- Result: failed with `ERR_MODULE_NOT_FOUND` for `scripts/harness/install-hooks.mjs`.
- Meaning: the test exercised the requested public installer module before implementation existed.

## GREEN

- Command: `node --test scripts/harness/test/install.test.mjs`
- Result: 4/4 passed.
- Command: `node --test scripts/harness/test/guard.test.mjs scripts/harness/test/audit.test.mjs scripts/harness/test/install.test.mjs`
- Result: 50/50 passed across 17 top-level tests and 4 suites.
- Integration coverage uses fresh temporary Git repositories for nested-root discovery, two-run idempotency, clean commit acceptance, `pwc/blocked.txt` rejection, and propagation of a present plugin guard failure.

## Commit

- Message: `feat(harness): install tracked hooks idempotently`
- Commit: recorded after this report is written; see `git log -1 --format=%H`.

## Scope Proof

- Task paths only: `.claude/commands/harness-check.md`, `HARNESS.md`, `scripts/harness/install-hooks.mjs`, `scripts/harness/install-hooks.sh` (deleted), `scripts/harness/hooks/pre-commit`, `scripts/harness/test/install.test.mjs`, and this report.
- `git diff --cached --name-only` was inspected before commit.
- The commit uses explicit pathspecs so pre-staged `SOUL.md` remains staged but is excluded from the Task 4 commit.
- No `pwc/` path or other unrelated working-tree path is staged or committed by Task 4.
