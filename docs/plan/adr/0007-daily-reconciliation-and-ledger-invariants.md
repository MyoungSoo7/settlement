# ADR 0007 — 일일 대사 + 복식부기 원장 불변식

- 상태: Accepted
- 일자: 2026-02-18

## 컨텍스트

정산(Settlement)은 결제(Payment)로부터 파생되는 금액이다. 두 값이 어긋나면 셀러에게
잘못된 금액이 지급되고, 회계상으로도 매출·수수료·미지급금이 틀어진다. 단순히 `settlements`
한 테이블에 금액만 적재하면 다음을 보장할 수 없다:

- **금액 정합성**: `paymentAmount = netAmount + commission` 이 깨진 정산이 조용히 통과
- **회계 추적성**: "이 정산이 매출·수수료·미지급금에 각각 얼마를 반영했나"를 사후 재구성 불가
- **누락 탐지**: 결제는 있는데 정산이 안 만들어졌거나, 정산 금액이 결제와 다른 경우를 발견 못 함

금융 도메인에서 이런 무성 드리프트는 곧 금전 사고다. 따라서 정산 위에 **복식부기 원장**을
얹어 회계 불변식을 강제하고, **일/기간 대사**로 `settlements` vs `payments` 불일치를 주기적으로
탐지한다.

## 결정

### 1. 복식부기 원장 (LedgerEntry)

`ledger` 도메인에 한 쌍의 분개(차변+대변)를 표현하는 순수 POJO `LedgerEntry` 를 둔다
(`ledger/domain/LedgerEntry.java`). 핵심 불변식은 도메인 생성 시점(`LedgerEntry.of(...)` →
`validate()`)에 강제한다:

- `amount > 0` — 부호는 금액이 아니라 `debitAccount`/`creditAccount` 가 결정
- `debitAccount != creditAccount` — 같은 계정 간 분개 금지
- `referenceId`/`referenceType`/`entryType`/`settlementDate` 필수

계정과목은 `AccountType` enum 으로 고정: `ACCOUNTS_RECEIVABLE`, `ACCOUNTS_PAYABLE`,
`REVENUE`, `COMMISSION_REVENUE`, `COMMISSION_EXPENSE`, `SALES_REFUND`, `CASH`.

### 2. 정산 1건 → 분개 2 row

정산 확정(`status=DONE`) 시 `SingleLedgerEntryWriter.write(settlementId)` 가 정산 1건을 두 row 로
분해한다:

```
row1: Dr ACCOUNTS_PAYABLE / Cr REVENUE              = netAmount    (셀러 미지급 + 매출 인식)
row2: Dr COMMISSION_EXPENSE / Cr COMMISSION_REVENUE = commission   (수수료 비용/수익 인식)
```

작성 직전 도메인 invariant 를 재검증한다: `netAmount + commission == paymentAmount` 가 아니면
`IllegalStateException` 으로 거부한다. 환불은 `ReverseEntryService.reverseForRefund(...)` 가
commission rate 비율로 `(refundedNet, refundedCommission)` 을 분해해 역분개(`SALES_REFUND` 차변)를
작성하며, `refundedNet + refundedCommission == refundAmount` 합계 정확성을 잔여 계산으로 보장한다.

### 3. 상태 머신 PENDING → POSTED → REVERSED

`LedgerStatus` 는 `PENDING → POSTED → REVERSED` 만 허용한다(`canTransitionTo`). REVERSED 는 종결
상태이며 원 entry 는 불변(Immutable) — 정정은 신규 entry 작성으로만 표현한다. 정산 분개는 작성 즉시
`post()` 되어 POSTED 로 전기된다.

### 4. 건별 트랜잭션 격리

일괄 처리(`CreateLedgerEntryService.createFromSettlements`)는 writer 를 프록시 경유로 호출해 각
정산이 `REQUIRES_NEW` 독립 트랜잭션으로 커밋되게 한다. 한 정산의 invariant 위반이 같은 배치의
다른 정산 분개를 롤백시키지 않는다. 멱등은 `existsByReference(settlementId, SETTLEMENT)` 로 보장.

### 5. 일/기간 대사

대사는 두 축으로 동작한다:

- **정산↔결제 대사**: settlement 가 자기 `settlement_db` 의 합계와 order 원천 합계를 비교한다.
  order 원천은 `/internal/recon` 내부 API(일일/기간 합계)로 가져온다(ADR 0020 — cross-DB 연결 0).
- **PG 정산파일 대사**: `pgreconciliation` 도메인의 `PgReconciliationMatcher` 가 PG 파일과 내부 결제
  원장을 `pg_transaction_id` 키로 비교해 `DiscrepancyType` 5종(`AMOUNT_MISMATCH`, `MISSING_INTERNAL`,
  `MISSING_PG`, `DUPLICATE`, `ROUNDING_DIFF`)으로 분류한다. 1원 미만 차이(`ROUNDING_THRESHOLD`)는
  `ROUNDING_DIFF` 로 자동 보정 대상, 그 외는 운영자 검토 대상이다.

## 결과

### 좋아지는 점

- 정산 금액이 결제와 어긋난 채로 확정될 수 없다 — invariant 가 분개 작성 시 차단
- 매출·수수료·미지급금·환불을 계정과목 단위로 사후 재구성 가능(감사·회계 마감)
- PG 대사로 매출 누락·이중 청구를 운영자가 1건씩 엑셀 비교하지 않고 시스템이 사전 정렬
- 건별 트랜잭션 격리로 한 정산 오류가 배치 전체를 무너뜨리지 않음

### 트레이드오프 / 리스크

- 정산 1건당 분개 row 가 늘어 원장 테이블이 빠르게 커진다(파티셔닝·아카이빙 필요)
- 복식부기 모델 학습 비용 — 계정과목·차대 방향 설계가 회계 지식을 요구
- 대사는 사후 탐지라 실시간 차단은 아니다 — 발견까지 지연 존재(주기 단축으로 완화)

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **settlements 단일 테이블 금액만 적재** | ✗ | 회계 추적·invariant 강제 불가. 누락을 사후에도 재구성 못 함 |
| **단식부기(거래 1 row)** | ✗ | 차대 균형 검증 불가 — 금융 회계 표준 미충족 |
| **복식부기 원장 + 일/PG 대사 (본 결정)** | ✓ | invariant 강제 + 계정 단위 추적 + 불일치 자동 분류 |
| **외부 회계 ERP 연동** | △ 보류 | 운영 규모 확대 시 검토 — 현 단계는 도메인 내 원장으로 충분 |

## 참조

- [0002 — Settlement 상태 머신](0002-settlement-state-machine.md)
- [0004 — Reverse Settlement via Adjustment](0004-reverse-settlement-via-adjustment.md)
