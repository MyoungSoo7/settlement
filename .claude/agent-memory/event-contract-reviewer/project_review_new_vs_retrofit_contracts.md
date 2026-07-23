---
name: project-review-new-vs-retrofit-contracts
description: Distinguish "new contract topic" changes from "retrofit contract test coverage onto existing untouched production code" via git status before reviewing
metadata:
  type: project
---

Not every event-contract-reviewer task is reviewing genuinely new producer/consumer behavior. On
2026-07-17 the task was to review 2 new contract topics
(`lemuel.loan.disbursement_requested`, `lemuel.company.reputation_changed`) plus a consumer
contract test added for the pre-existing `lemuel.user.registered` topic in company-service. Checking
`git status --porcelain` up front showed the producer/consumer production classes
(`LoanEventPublisherAdapter`, `CompanyReputationEventPublisherAdapter`,
`LoanDisbursementRequestedConsumer`, `CompanyReputationChangedConsumer`,
`UserRegisteredEventConsumer`) were **not modified** — only schema/sample files, contract test files,
`build.gradle.kts` (testFixtures dep), and the ADR were changed. So the "change" was retroactively
formalizing an existing, already-working event flow into ADR 0024's contract-as-code discipline, not
introducing new runtime behavior.

**Why this matters for review**: when production code is untouched, the review should weight much
more heavily toward "does the retrofitted schema accurately describe what the code has always done"
(and toward SPEC.md/doc sync, since that's the part most likely to lag) rather than toward "did this
new code introduce a bug" — the runtime risk is inherently lower since the flow was already live and
presumably working. Still worth the full 3-way check (schema↔producer↔consumer), but frame findings
accordingly (e.g. a schema/sample mismatch here means the retrofit got the description wrong, not
that a live bug was just introduced).

**How to apply**: Start every event-contract-reviewer task with `git status --porcelain` /
`git diff --stat` and note which of {schema, sample, producer test, consumer test, producer
production code, consumer production code, SPEC.md, ADR} actually changed, before diving into
field-by-field validation. See also [[feedback_spec_md_drift_check]].
