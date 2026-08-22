# ADR 0008 — 캐시플로우 리포트 도메인

- 상태: Accepted
- 일자: 2026-01-30

## 컨텍스트

운영·재무팀은 "기간별 자금 흐름"(총 결제액·환불·수수료·순 정산액·환불률)을 한눈에 보고, 결제→정산
파이프라인에 **금액 누수가 없는지** 검증하고 싶어 한다. 이 요구를 정산(`settlement`)·지급(`payout`)·
원장([0007](0007-daily-reconciliation-and-ledger-invariants.md)) 같은 거래 도메인 안에 끼워 넣으면, 읽기 전용 집계·검증·문서
생성 로직이 거래 쓰기 경로를 오염시키고 책임이 흐려진다.

또한 리포트는 단순 합산을 넘어 **회계 불변식 대사**를 포함해야 한다. 결제 - 환불이 정산 net + commission
과 맞는지, adjustment 합이 연결 환불과 맞는지([0004](0004-reverse-settlement-via-adjustment.md)),
Outbox 발행 건수가 생성 정산 수와 맞는지 같은 시스템 전체 불변식을 한곳에서 검증해야 한다.

## 결정

정산/지급/원장 데이터를 읽어 캐시플로우를 집계·대사·문서화하는 **별도 `report` 도메인**을 둔다.
거래 도메인은 건드리지 않고 읽기 전용으로 소비한다(헥사고날 포트로 분리).

### 1. 도메인 모델 (`report.domain`)

- `CashflowReport` (record): 기간·`BucketGranularity`·`CashflowTotals`·`List<CashflowBucket>`·
  `CashflowReconciliation` 를 묶는 Aggregate. from ≤ to, granularity 필수 등 불변식을 생성자에서 강제.
- `CashflowTotals.from(buckets)`: 버킷 합으로 GMV·환불·수수료·net·환불률(refunded/gmv, scale 4) 계산.
- `CashflowReconciliation` / `ReconciliationCheck`: 대사 결과(통과/불일치, 기대값·실제값·차이) 표현.

### 2. 읽기 전용 유스케이스 (`GenerateCashflowReportService`)

`@Transactional(readOnly = true)` 서비스가 `LoadCashflowAggregatePort`(집계)와
`LoadPeriodReconciliationPort`(대사 합산)를 통해서만 데이터를 읽는다. `CashflowReportCommand` 는
기간 최대 366일, sellerId nullable(시스템 전체 vs 판매자 단위)을 검증.

### 3. 대사 3종 불변식

판매자 단위가 아닌 시스템 전체 리포트에서만 실행:

1. `payments_minus_refunds_equals_settlement` — 결제(캡처) − 환불 = 정산 net + commission
2. `adjustments_equal_linked_refunds` — `|Σ(adjustments)| = Σ(linked refunds)` ([0004](0004-reverse-settlement-via-adjustment.md))
3. `outbox_published_equals_settlements_created` — Outbox PaymentCaptured PUBLISHED 수 = 생성 정산 수

불일치 시 `cashflow_reconciliation_mismatch_total` Counter 를 check 별 태그로 증가시켜
Alertmanager 가 감시하고, ERROR 로그를 남긴다. 생성 지연은 Timer 로 계측.

### 4. iText PDF 생성 (`adapter/out/pdf`)

`CashflowPdfAdapter` 가 `RenderCashflowReportPdfPort` 를 구현해 iText 8 로 PDF 를 렌더(헤더·기간·총계·
버킷·대사 섹션). 한글 폰트 임베딩, 대사 통과/실패 배지, 음수 강조 색상 포함. 정산 PDF 와 동일 스타일을
따라 시각적 일관성을 유지하며, 실패 시 `CashflowPdfRenderException` 으로 감싼다.

### 5. 감사 로깅

`generate(...)` 에 `@Auditable(action = CASHFLOW_REPORT_ACCESSED, ...)` 를 붙여 리포트 접근
(기간·granularity·sellerId)을 감사 로그로 남긴다.

## 결과

### 좋아지는 점
- 읽기 전용 리포트 책임을 거래 도메인에서 분리 — 쓰기 경로 무오염
- 회계 불변식 대사를 리포트에 내장해 금액 누수를 정기 탐지(Alert 연계)
- PDF·집계·대사를 포트로 분리해 데이터소스/렌더러 교체 용이

### 트레이드오프 / 리스크
- 리포트 집계가 거래 도메인과 별도 쿼리 → 스키마 변경 시 양쪽 동기화 필요
- 대사 불변식 #3 은 기간 경계 시각 오차가 있어 월 단위 권장(짧은 기간 거짓 불일치 가능)
- PDF 폰트 임베딩·iText 의존으로 빌드·런타임 비용 추가

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 정산 도메인 내부에 리포트 로직 포함 | ✗ | 읽기/쓰기 책임 혼재, 쓰기 경로 오염 |
| 외부 BI 도구(Metabase 등)만 사용 | △ | 임시 탐색엔 유리하나 대사 불변식·PDF·감사 미충족 |
| **별도 report 도메인 + 포트 분리 + iText PDF (본 결정)** | ✓ | 책임 분리 + 불변식 대사 + 문서 산출 |

## 참조

- [0004 — 환불 시 역정산 (SettlementAdjustment)](0004-reverse-settlement-via-adjustment.md)
- [0007 — 복식부기 원장](0007-daily-reconciliation-and-ledger-invariants.md)
