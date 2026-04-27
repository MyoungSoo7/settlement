# ADR 0008 — Cashflow Report 를 별도 도메인으로 분리

**Status:** Accepted
**Date:** 2026-04-23

## Context

T3-⑨ 에서 "자금 흐름 리포트" 요구사항이 들어왔다. 기존 settlement 도메인 안에 넣을지 vs 신규 도메인으로 분리할지가 설계 선택.

- 방안 A: `settlement.application.service.CashflowReportService` — 한 도메인 내에서 완결.
- 방안 B: 신규 `report` 도메인 패키지.

## Decision

**B 채택 — 신규 `report` 도메인.**

**이유:**

1. Cashflow 는 **여러 원장**을 가로지른다 — payments / refunds / settlements / settlement_adjustments / outbox_events / orders / products. Settlement 만의 책임이 아니다.
2. 리포트 확장 방향 (PDF, 판매자별, 대사) 이 settlement 도메인 규칙과 분리돼 있다.
3. 아웃바운드 포트(`LoadCashflowAggregatePort`, `LoadPeriodReconciliationPort`, `RenderCashflowReportPdfPort`) 가 report 도메인에 응집되어 테스트·교체 용이.

**구조:**

```
report/
├── domain/                             # POJO
│   ├── BucketGranularity, CashflowBucket, CashflowTotals, CashflowReport
│   └── ReconciliationCheck, CashflowReconciliation
├── application/
│   ├── port/in/GenerateCashflowReportUseCase
│   ├── port/out/LoadCashflowAggregatePort, LoadPeriodReconciliationPort, RenderCashflowReportPdfPort
│   └── service/GenerateCashflowReportService
└── adapter/
    ├── in/web/ReportController + dto
    └── out/
        ├── persistence/CashflowAggregateQueryAdapter, PeriodReconciliationJdbcAdapter
        └── pdf/CashflowPdfAdapter
```

**규칙 준수:**
- report 도메인이 settlement 의 `QSettlementJpaEntity` 를 읽는 건 허용 (adapter 레이어 내부). settlement 서비스나 유스케이스는 호출하지 않음.
- 교차 테이블 JDBC 는 settlement 의 `DailyTotalsJdbcAdapter` 와 같은 패턴으로 report 도메인 어댑터에서 직접.

## Consequences

**Positive**
- 리포트 확장(판매자별, PDF, 알림)이 settlement 에 영향 없이 가능.
- 대사 불변식이 한 자리에 모여 있어 새로운 불변식 추가가 용이.
- `ReportController` 가 settlement 와 독립된 API 경로 `/api/reports/*` 로 정렬.

**Negative / Trade-offs**
- JDBC 쿼리가 report 와 settlement 양쪽에 중복 유사(기간 vs 일자 변형) → 향후 추출 가능성.
- report 어댑터가 다른 도메인의 테이블명을 하드코드 — 스키마 변경 시 같이 수정 필요.

## Related

- ADR 0001 — Hexagonal (경계 규칙 근거)
- ADR 0007 — 대사 불변식
- T3-⑨ 전체 파일 목록: `report/` 디렉토리
