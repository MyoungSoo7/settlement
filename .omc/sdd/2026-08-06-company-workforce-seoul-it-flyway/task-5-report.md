# Task 5 report — Seoul IT comparison scope disclosure

## RED

Command:

```text
gradlew.bat --no-daemon :company-service:test --tests "*WorkforceComparisonPersistenceAdaptersTest" --tests "*CompanyWorkforceControllerTest" --console=plain
```

Result: failed as expected for the new requirements.

- `WorkforceComparisonPersistenceAdaptersTest.findGroupStatistics` failed because the emitted SQL joined only `b.status = 'COMPLETE'` and did not require `b.coverage_scope = 'SEOUL_IT_FULL'`.
- `CompanyWorkforceControllerTest.detail` failed because `note` did not contain `2026년 7월 23일 배포본` or `서울 소프트웨어·IT 서비스 사업장`.

The run also exposed three pre-existing controller expectation failures: tests expect a 9% salary calculation (`43,750,000`) while the current implementation calculates using 9.5% (`41,447,368`).

## GREEN

Implemented:

- `WorkforceComparisonPersistenceAdapter` now joins build metadata only when both `status = 'COMPLETE'` and `coverage_scope = 'SEOUL_IT_FULL'`.
- `WorkforceComparisonResponse.note` now identifies the `2026년 7월 23일 배포본` and `서울 소프트웨어·IT 서비스 사업장`, retaining salary-cap and truncated-publication caveats.
- Money response fields remain `String` and continue using `BigDecimal.toPlainString()`.

The specified full command still fails only on the three unrelated 9%/9.5% controller expectations above; the new query-contract assertion no longer fails and the new note assertions pass.

## Commit

Pending commit: `fix(company): disclose Seoul IT comparison scope`

## Concerns

`CompanyWorkforceControllerTest` contains baseline expectations that conflict with the current 9.5% implementation. They were not changed because that calculation and its expectations are outside Task 5's requested query-scope/disclosure change.
