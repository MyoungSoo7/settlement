---
name: feedback-spec-md-drift-check
description: SPEC.md §5 event catalog drifts independently from ADR 0024 and shared-common fixtures — always diff it separately when reviewing a contract change
metadata:
  type: feedback
---

When reviewing an event-contract change (ADR 0024 / `event-contract-change` skill), always check
`SPEC.md` §5 (이벤트 카탈로그) as its own independent artifact — do not assume it was updated just
because `docs/adr/0024-event-contract-as-code.md`'s "Update" note and the
`EventContractFixtureTest` topic list were. In a real review (2026-07-17, adding
`lemuel.loan.disbursement_requested` + `lemuel.company.reputation_changed`), the ADR note and the
fixture test's `@ValueSource` were both correctly bumped to 14 topics, but `SPEC.md` still listed
both new topics under "부가(계약 스키마 없음)" (no contract schema) and still said "12개" — a stale,
now-false claim, confirmed via `git status` showing SPEC.md untouched by that diff.

**Why**: `git status`/`git diff --stat` is the fastest way to catch this — if the topic's schema/
sample/tests are all present in the diff but SPEC.md isn't in the changed-file list, that's the tell.
The event-contract-change skill's step 5 explicitly requires the SPEC.md §5 table row, but nothing
mechanically enforces it (no CI check found for SPEC.md topic-count sync), so it's the single most
likely miss in an otherwise-clean contract addition.

**How to apply**: In every event-contract-reviewer pass, grep the new topic name in `SPEC.md` — if it
only appears in the "부가(계약 스키마 없음)" footnote line (or not at all) while a schema file exists
under `shared-common/src/testFixtures/resources/contracts/events/`, flag it as a doc-drift finding
(Medium severity — no runtime failure, but it misleads future contributors into skipping the
schema/test wiring on the next change to that topic). Cite this as violating `event-contract-change`
skill step 5.

See also [[project_review_new_vs_retrofit_contracts]].
