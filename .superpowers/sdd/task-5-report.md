# Task 5 Report

## RED

- Added a live-repository assertion to `scripts/harness/test/audit.test.mjs` before changing contracts or STATUS.
- `node --test scripts/harness/test/audit.test.mjs` failed as intended with:
  - STATUS application claim `13`, actual `14`
  - STATUS migration claim `110`, actual `113`
  - STATUS test-class claim `517`, actual `529`
  - missing `harness-contract` block
  - contract cases not exposed through the required array contract
- The audit also reported the pre-existing untracked `.github/workflows/harness-guard.yml`, which is outside Task 5 scope.

## GREEN

- Both SKILL files now encode the manifest facts in exactly one `harness-contract` JSON block and describe the canonical scratch path, cycles 1 through 5, cycle-5 safety valve, explicit user adoption, user-confirmed boundary equivalence, Jaccard properties, and the inclusive `0.85` threshold.
- Both test-case files now contain exactly the six manifest transition cases.
- Fresh `git ls-files` measurements updated STATUS to application `14`, migration `113`, ADR `25`, and test classes `529`, dated `2026-07-13`.
- `node --test scripts/harness/test/audit.test.mjs`: 10 passed, 0 failed.
- `git diff --check -- <Task 5 paths>`: clean.
- `node scripts/harness/harness-audit.mjs`: contract and STATUS checks pass; the command exits 1 only for the unrelated untracked workflow noted above.

## Commit Scope

- `.claude/skills/interview-harness/SKILL.md`
- `.codex/skills/interview-harness/SKILL.md`
- `.claude/skills/interview-harness/test_cases.json`
- `.codex/skills/interview-harness/test_cases.json`
- `STATUS.md`
- `scripts/harness/test/audit.test.mjs`
- `.superpowers/sdd/task-5-report.md`

Commit: `fix(harness): align interview contract facts`.

## Follow-up Findings

- RED: the live-repository assertion failed on both stale surfaces: Claude contained forbidden `cycle > 5` wording and STATUS had `Last updated=2026-07-13` versus measurement basis `2026-07-12`.
- GREEN: the Claude flow now stops on cycle 5 below `0.85` with `safety_valve` and explicitly starts no cycle 6; the STATUS measurement basis is `2026-07-13`.
- The assertion reads live SKILL and STATUS content while retaining the tracked manifest as its oracle.
- Fresh verification: audit tests 10 passed, 0 failed; standalone audit retains only the unrelated untracked workflow failure.
