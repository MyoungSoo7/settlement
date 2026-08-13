# PRD — 정산 코어 (Settlement Core)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 새 기능을 제안하지 않는다 — 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목        | 값                                                                                                |
> | ----------- | ------------------------------------------------------------------------------------------------- |
> | 대상 범위   | `settlement-service`(8082, mgmt 8083, DB `settlement_db`) + `order-service` 연계면                |
> | 역산 기준   | 2026-08-12 `develop` 브랜치 작업트리                                                              |
> | 근거        | 도메인/서비스/어댑터 소스, Flyway 42개 마이그레이션, `application.yml`, shared-common `SecurityConfig` |
> | 범위 밖     | loan · investment · card · insurance · deposit · organization 등 (§2.2 경계만 기술)                |
> | 관련 문서   | [`../../../SPEC.md`](../../../SPEC.md) §3.2 · [`../../../CLAUDE.md`](../../../CLAUDE.md) · [`../../adr`](../adr/) · `settlement-domain-rules` 스킬 |

**읽는 법**: FR(기능 요구)·BR(비즈니스 규칙)·NFR(비기능)에 번호를 달았고, 각 항목의 "근거"는 그 요구가 실제로
구현·강제되는 지점이다. 요구와 코드가 어긋난 곳은 §12 에 격차로 따로 모았다(문서를 코드에 맞춰 미화하지 않는다).

---

## 1. 배경과 문제

이커머스 플랫폼은 구매자에게서 **한 번에** 돈을 받고, 판매자(셀러)에게는 **나중에 나눠서** 준다. 그 사이에
수수료·부가세·원천징수·환불·차지백·PG 정산차이·대출 상환 차감이 끼어든다. 이 간극을 관리하지 못하면 세 가지가 동시에 깨진다.

| 깨지는 것     | 구체적 손상                                                                    |
| ------------- | ------------------------------------------------------------------------------ |
| **돈**        | 과다지급(환불된 주문까지 지급) · 과소지급 · 이중지급 · 지급 후 회수 불가       |
| **장부**      | 차대 불일치, 사후 수정으로 인한 감사 추적 소실, PG 실제 입금과 내부 장부의 괴리 |
| **셀러 신뢰** | "왜 이 금액인가"에 답하지 못함, 지급일 불확실, 보류금 근거 불명                |

정산 코어는 이 문제를 **결제 1건 → 정산 1건 → 지급 1~2건 → 원장 전표 N건**의 추적 가능한 사슬로 바꾸고,
그 사슬의 모든 금액 변경을 *수정이 아닌 추가*(조정·역분개)로만 허용해 해결한다.

## 2. 목표 / 비목표

### 2.1 목표

- **G1 — 자동 정산**: 결제 캡처 이벤트만으로 사람 개입 없이 정산이 생성되고, 정산일이 되면 배치가 확정한다.
- **G2 — 금액의 설명가능성**: 임의의 정산 1건에 대해 "결제액 → 수수료 → net → 보류 → 세금 → 실지급"의 전 과정과
  적용 요율의 **근거**까지 조회할 수 있다.
- **G3 — 이력 불변**: 확정(DONE)·전기(POSTED) 이후의 금액은 절대 덮어쓰지 않는다. 정정은 조정 레코드와 역분개로만 한다.
- **G4 — 복식부기 정합**: 모든 금전 사건이 차1·대1 전표로 남고, 월 단위 시산표가 균형을 이룬 뒤에만 기간을 마감한다.
- **G5 — MSA 경계 유지**: order 의 코드·DB 에 의존하지 않고도 주문·결제·셀러·상품 정보를 조회하고 대사한다.
- **G6 — 자기 진단**: 운영자가 "지금 정합성이 깨졌는가"를 API 한 번으로 확인할 수 있다.

### 2.2 비목표 (명시적 경계)

- **주문·결제 자체를 소유하지 않는다.** 상태 원본은 order-service 다. 정산은 프로젝션 사본만 읽는다.
- **여신 판단을 하지 않는다.** 선정산/기업대출 심사는 loan-service. 정산은 상환 차감 결과(`loan.repayment_applied`)만 받는다.
- **전사 GL 을 소유하지 않는다.** 정산은 *자기 도메인 원장*을 갖고, 전사 계정계 집계는 account-service 가 별도로 한다.
- **PG 사와 직접 정산하지 않는다.** PG 정산파일을 받아 대사할 뿐, 대금 청구·수취 협상은 범위 밖이다.
- **셀러 UI 를 소유하지 않는다.** REST 만 제공하고 화면은 frontend 몫이다.

## 3. 사용자와 이해관계자

| 사용자        | 역할(JWT)         | 핵심 관심사                                  | 주 사용 인터페이스                                             |
| ------------- | ----------------- | -------------------------------------------- | -------------------------------------------------------------- |
| 셀러          | `USER`            | 내 지급 계좌, 내 세금계산서                  | `/api/seller/bank-account` · `/api/tax-invoices/**`             |
| CS 담당       | `MANAGER`         | "이 정산 왜 이 금액?" 문의 응대              | `/settlements/**` · `/api/settlements/query/**`                 |
| 재무·회계     | `ADMIN`/`MANAGER` | 원장·시산표·월마감·세무 전표                 | `/api/ledger/**` · `/admin/ledger-periods/**` · `/admin/tax/**` |
| 정산 운영자   | `ADMIN`           | 지급 실행·재시도, 차지백, PG 대사, 요율 정책 | `/admin/payouts/**` · `/admin/chargebacks/**`                   |
| 온콜 엔지니어 | `ADMIN`           | 정합성 이상, DLQ, 이벤트 격리·재처리         | `/admin/integrity/**` · `/admin/dlq/**` · `/admin/event-track/**` |
| 타 서비스     | 내부 키           | 정산 확정·지급 완료 이벤트 소비              | Kafka (§9.2)                                                    |

## 4. 제품 범위 — 기능 맵

정산 코어는 **12개 서브도메인**으로 구성된다(패키지 = 서브도메인).

| #   | 서브도메인         | 한 줄 책임                                                | 진입점                                |
| --- | ------------------ | --------------------------------------------------------- | ------------------------------------- |
| 1   | `settlement`       | 정산 생성·확정·조정·홀드백·요율정책·재실행                | Kafka 컨슈머 + 배치 + 조회 REST       |
| 2   | `payout`           | 셀러 송금 실행·재시도·반송, 지급 계좌 레지스트리          | 배치 + `/admin/payouts` + 셀러 셀프   |
| 3   | `ledger`           | 복식부기 전표, 역분개, 기간 마감, 확정 시산표             | 로컬 아웃박스 + `/api/ledger`         |
| 4   | `tax`              | 부가세·원천징수 산출, 세금계산서 발행·스캔 매칭           | `/admin/tax` + `/api/tax-invoices`    |
| 5   | `chargeback`       | 지급 거절·분쟁 접수와 수용/거절                           | `/admin/chargebacks`                  |
| 6   | `pgreconciliation` | PG 정산파일 업로드 → 대사 → 차이 승인/거절(역정산 트리거) | `/admin/pg-reconciliation`            |
| 7   | `recovery`         | 지급 후 회수 채권(원금·상계·정체 이관)                    | `/admin/recoveries` + 배치            |
| 8   | `closing`          | 정보계 월마감 — 셀러 월 정산 마트 적재                    | `/admin/monthly-closing` + 배치       |
| 9   | `report`           | 캐시플로우 리포트(JSON/PDF)                               | `/api/reports`                        |
| 10  | `recon`            | order 와의 교차 대사(내부 API 상호 호출)                  | `/internal/recon` + `OrderReconClient` |
| 11  | `integrity`        | 정합성 자가진단 8종                                       | `/admin/integrity`                    |
| 12  | `idempotency`      | 수기 운영 조작의 멱등 보장(횡단 지원)                     | 내부(`manual_operation_idempotency`)  |

> `../../../SPEC.md` §3.2 는 이 중 7개만 표에 담고 있다 — 로스터 드리프트는 §12-A 참조.

## 5. 핵심 유스케이스

### UC-1. 결제 → 정산 → 지급 (해피패스)

```
order-service                    settlement-service
  payment.captured  ──Kafka──►  ① 정산 생성 (REQUESTED)
                                   · 셀러 등급으로 요율·주기·홀드백 결정
                                   · commission_rate 스냅샷 저장
                                ② 매일 03:00 배치: 정산일 도래분 확정 (→ DONE)
                                   · 청크 100건 단위 커밋
                                   · 회수채권 상계 → 원천징수 공제
                                   · 원장 전표 아웃박스 적재 + 이벤트 발행
                                ③ 매일 04:00 배치: 지급 실행 (Payout IMMEDIATE)
                                   · 일일 한도 검사 → 펌뱅킹 전송 → COMPLETED
                                ④ 보류 해제일 도래: 03:00 배치 → Payout HOLDBACK_RELEASE
```

### UC-2. 환불 발생 (역정산)

`payment.refunded` 수신 → 정산이 **미확정**이면 `net = 결제액 − 누적환불액 − 수수료` 로 재계산(0 이하면 CANCELED),
**확정(DONE)**이면 원본을 건드리지 않고 `settlement_adjustments` 에 **음수 조정**을 추가하고 원장은 역분개한다.
이미 지급까지 끝났다면 잔여 holdback 에서 우선 흡수하고, 그래도 남으면 **회수 채권**(UC-5)으로 넘긴다.

### UC-3. PG 대사

운영자가 PG 정산파일을 업로드 → 내부 결제 행과 매칭 → 차이(금액불일치·내부누락·PG누락·중복·라운딩·수수료불일치)를
목록화 → 운영자가 건별 승인/거절 → **승인된 차이는 `lemuel.pgreconciliation.discrepancy_approved` 로 자기 자신에게
발행되어 역정산(clawback)으로 반영**된다. 승인 전 `clawback-preview` 로 영향 금액을 미리 볼 수 있다.

### UC-4. 원장 월마감

`GET /admin/ledger-periods/{ym}/trial-balance` 로 계정별 차/대 합계와 균형 여부 확인 →
`POST /{ym}/close` 로 마감. **불균형이면 422 로 거부**되고, 마감된 기간에는 신규 전표가 들어가지 못한다(`LedgerPeriodGuard`).

### UC-5. 지급 후 회수

지급이 끝난 뒤 발생한 환불·차지백·대사 회수 중 holdback 으로 흡수되지 못한 잔액이 **채권 원금**이 된다.
이후 그 셀러의 정산이 확정될 때마다 즉시지급분에서 자동 상계되고, 일정 기간 상계가 없으면
`MANUAL_REQUIRED` 로 이관되어 사람이 처리한다.

### UC-6. 세무 처리

정산 확정 시 수수료를 **부가세 포함 금액**으로 보고 공급가액/부가세를 갈라내고, 개인 셀러면 원천징수 3.3% 를
**실제 송금액에서 공제**한다. 세금계산서는 발행 API 로 생성되며, 셀러가 올린 계산서 스캔본은 OCR 로 읽어 자동 매칭한다.

## 6. 기능 요구사항

### 6.1 정산 (FR-S)

| ID    | 요구                                                                                                                     | 근거                                                            |
| ----- | ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| FR-S1 | 결제 캡처 이벤트 1건은 정산 1건을 만든다. 중복 수신은 새 정산을 만들지 않는다(`settlements.payment_id` UNIQUE).           | `PaymentEventKafkaConsumer` · `CreateSettlementFromPaymentService` |
| FR-S2 | 정산 생성 시 셀러 등급에서 **수수료율·정산주기·홀드백율**을 결정하고, 적용 요율과 그 **근거 문자열**을 영구 저장한다.     | `SellerTier` · `CommissionRatePolicy` · `settlements.commission_rate(_source)` |
| FR-S3 | 정산일이 도래한 정산은 배치가 청크(기본 100건) 단위로 확정한다. 저장·이벤트·원장 아웃박스·색인이 같은 커밋에 묶인다.      | `SettlementConfirmJobConfig` · `SettlementConfirmItemWriter`     |
| FR-S4 | REST 로 정산을 **생성·수정할 수 없다.** 조회·검색·집계만 제공한다.                                                       | `SettlementController`(GET only)                                |
| FR-S5 | 정산 1건은 PDF 명세서로 출력할 수 있다.                                                                                  | `GET /settlements/{id}/pdf`                                     |
| FR-S6 | 운영자는 특정 일자의 정산 배치를 재실행할 수 있으나, 소급 범위는 기본 **90일**로 제한된다(초과 시 400).                   | `RerunSettlementBatchService` · `app.settlement.rerun.max-lookback-days` |
| FR-S7 | 수수료 정책은 SELLER > TIER > 등급기본 순으로 해석되며 **미래 구간에만** 등록할 수 있다. 확정 없는 시뮬레이션을 제공한다. | `CommissionRateAdminController` · `SimulateCommissionRateService` |

### 6.2 홀드백 (FR-H)

| ID    | 요구                                                                                                    | 근거                                                        |
| ----- | ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| FR-H1 | net 에 등급별 보류율을 적용해 보류액을 산출하고, 해제 예정일은 **영업일 기준**으로 계산한다(공휴일 반영). | `Settlement.applyHoldback` · `BusinessDayCalculator` · `app.settlement.extra-holidays` |
| FR-H2 | 해제일이 지난 보류는 매일 03:00 배치가 자동 해제하고 별도 지급(`HOLDBACK_RELEASE`)을 만든다.             | `HoldbackReleaseScheduler` · `PayoutType`                   |
| FR-H3 | 환불·회수는 **보류액에서 우선 차감**한다. 보류로 충당되면 셀러 실지급에는 영향이 없다.                   | `Settlement.consumeHoldbackForRefund`                       |
| FR-H4 | 운영자는 해제 전 보류 현황을 미리 조회할 수 있다.                                                       | `GET /admin/settlements/holdback-preview`                   |

### 6.3 지급 (FR-P)

| ID    | 요구                                                                                                       | 근거                                                       |
| ----- | ---------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| FR-P1 | 지급액 = `즉시지급분 − 원천징수 − 회수상계`. **차감 순서 고정**(T-4): 원천징수를 먼저 확보하고 그 잔여만 상계 가용액으로 넘긴다 — 못 뗀 채권은 이월되지만 못 뗀 세금은 소실되기 때문. | `SettlementConfirmItemWriter`                              |
| FR-P2 | 한 정산은 **지급 유형별 최대 1건**의 지급만 만든다(`(settlement_id, payout_type)` 멱등).                    | `PayoutType` + DB 제약                                     |
| FR-P3 | 지급은 시스템 일일 한도(기본 10억)와 셀러 일일 한도(기본 1억)를 넘길 수 없다.                               | `PayoutLimitChecker` · `app.payout.*-daily-limit`          |
| FR-P4 | `COMPLETED` 지급은 반드시 펌뱅킹 거래 ID 를 갖는다(사후 추적 보장).                                        | `Payout` 불변식                                            |
| FR-P5 | 재시도는 `FAILED` 에서만 가능하다. 송금 중(`SENDING`) 취소는 불허한다.                                      | `Payout.retry` · `PayoutStatus`                            |
| FR-P6 | 은행 반송(bounce)은 별도 기록으로 남고 재지급 대상이 된다.                                                 | `PayoutBounce` · `RecordPayoutBounceService`               |
| FR-P7 | 셀러 계좌번호는 저장 시 암호화하고 노출 시 마스킹한다. 키 교체용 재암호화 운영 API 를 제공한다.             | `V20260716200200__payout_bank_account_encryption` · `ReencryptPayoutPiiService` |
| FR-P8 | 셀러 셀프 계좌 등록은 식별자를 **JWT 주체에서만** 파생한다(요청 파라미터 신뢰 금지 — IDOR 차단).            | `SellerBankAccountSelfController`                          |

### 6.4 원장 (FR-L)

| ID    | 요구                                                                                                       | 근거                                            |
| ----- | ---------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| FR-L1 | 전표 1행 = `(차변계정, 대변계정, 양수 금액)` 한 쌍. 금액·계정·참조는 생성 후 변경 불가.                     | `LedgerEntry`(final 필드, setter 없음)          |
| FR-L2 | 상태는 `PENDING → POSTED → REVERSED` 뿐이며, 정정은 역분개 후 신규 전표로만 한다.                           | `LedgerStatus` · `ReverseEntryService`          |
| FR-L3 | 원장 적재는 **로컬 아웃박스**(Kafka 미경유)로 비동기 처리한다. 실패 건은 조회·재큐잉할 수 있다.             | `LedgerOutboxPoller`(5초) · `/admin/outbox/ledger` |
| FR-L4 | 기간 마감은 확정 시산표가 **균형일 때만** 성공하고, 마감 시점 합계를 스냅샷으로 못박는다. 재개봉은 없다.    | `LedgerPeriod.close` · `CloseLedgerPeriodService` |
| FR-L5 | 마감된 기간에는 신규 전표를 넣을 수 없다.                                                                  | `LedgerPeriodGuard`                             |
| FR-L6 | 정산·환불 기준으로 전표를 조회할 수 있다.                                                                  | `GET /api/ledger/settlements/{id}` · `/refunds/{id}` |

### 6.5 세무 (FR-T)

| ID    | 요구                                                                                             | 근거                                        |
| ----- | ------------------------------------------------------------------------------------------------ | ------------------------------------------- |
| FR-T1 | 부가세는 **포함과세**로 산출한다: `vat = floor(commission × 10/110)`, `공급가액 = commission − vat`. | `TaxCalculation`                            |
| FR-T2 | 개인(비사업자) 셀러는 `withholding = floor(net × 3.3%)` 를 실지급액에서 공제한다. 사업자는 0.     | `TaxCalculation.WITHHOLDING_RATE`           |
| FR-T3 | 세무 프로필 미등록 셀러는 **사업자로 취급**(원천징수 0)하되 경고 로그를 남긴다.                   | `SettlementConfirmItemWriter`               |
| FR-T4 | 모든 세무 금액은 **원단위 절사**하고, 산출 직후 대사 항등식을 자가검증한다.                       | `TaxRounding` · `TaxCalculation`            |
| FR-T5 | 세금계산서를 발행·조회하고 PDF 로 출력한다. 셀러는 자기 정산분만 조회한다.                        | `IssueTaxInvoiceService` · `TaxInvoiceSellerController` |
| FR-T6 | 셀러가 업로드한 계산서 스캔본을 OCR 로 읽어 사업자번호·금액으로 자동 매칭하고, 운영자가 거절·재매칭할 수 있다. | `TaxInvoiceScanService` · `TaxInvoiceScanMatcher` |

### 6.6 차지백·PG 대사·회수 (FR-C / FR-R / FR-V)

| ID    | 요구                                                                                           | 근거                                              |
| ----- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| FR-C1 | 차지백은 사유(사기·중복·미수취·상품상이)와 출처를 갖고 `OPEN → ACCEPTED/REJECTED` 로만 종결된다. | `Chargeback` · `ChargebackReason`                 |
| FR-R1 | PG 정산파일 업로드 시 파일 SHA-256 을 기록해 동일 파일 재처리를 식별한다.                       | `V20260719120000__pg_recon_run_file_sha256`       |
| FR-R2 | 대사 실행은 `RUNNING → COMPLETED/FAILED`, 차이는 `PENDING → APPROVED/REJECTED` 로 관리한다.     | `ReconciliationRunStatus` · `DiscrepancyStatus`   |
| FR-R3 | 승인된 차이만 역정산으로 반영되며, 반영은 **자기 발행 이벤트 소비**로 비동기 처리한다.          | `PgReconciliationOutboxEventAdapter` → `PgReconciliationApprovedSettlementAdjustConsumer` |
| FR-V1 | 회수 채권의 원금은 발생 후 불변이고, 상계는 누적으로만 진행되며 잔액 검증·종결 전이는 도메인이 소유한다. | `SellerRecovery`                                  |
| FR-V2 | 일정 기간 자동 상계가 없는 채권은 `MANUAL_REQUIRED` 로 이관된다.                                | `EscalateStaleSellerRecoveryService`(03:30)       |

### 6.7 대사·정합성·운영 (FR-O)

| ID    | 요구                                                                                                        | 근거                                              |
| ----- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| FR-O1 | 일일 대사는 settlement 자기 합계와 order 내부 API 합계를 비교한다. **양측 모두 자기 DB 만 읽는다.**          | `DailyReconciliationScheduler`(05:00) · `OrderReconClient` |
| FR-O2 | 정합성 자가진단 8종: 원장완전성·지급대사·반송대사·보류현황·정체상태·환불조정·처리이벤트수·프로젝션차이.      | `IntegrityAdminController`                        |
| FR-O3 | 매일 06:00 정합성 모니터가 자동 순회한다.                                                                   | `IntegrityMonitorScheduler`                       |
| FR-O4 | 소비 이벤트는 정상·중복·격리 3분류로 추적되고, 격리분은 재처리할 수 있다.                                    | `/admin/event-track` · `quarantined_events` · `duplicate_events` |
| FR-O5 | Kafka DLQ 를 조회하고 선택 재처리할 수 있다.                                                                | `/admin/dlq` · `DlqReplayService`                 |
| FR-O6 | 월마감 마트는 **완결된 과거 월**만 대상이며, 재실행은 기간 단위 교체(멱등)다. 원장 마감된 기간 재마감은 409. | `MonthlyClosingRun` · `RunMonthlyClosingService`  |

## 7. 도메인 규칙 (BR)

### 7.1 상태머신

```
Settlement  : REQUESTED → PROCESSING → DONE | FAILED | CANCELED   (FAILED → REQUESTED 재시도)
Payout      : REQUESTED → SENDING → COMPLETED | FAILED            (CANCELED 는 REQUESTED·FAILED 에서만)
Ledger      : PENDING → POSTED → REVERSED
LedgerPeriod: OPEN → CLOSED (종결, 재개봉 없음)
Chargeback  : OPEN → ACCEPTED | REJECTED
PgReconRun  : RUNNING → COMPLETED | FAILED     Discrepancy: PENDING → APPROVED | REJECTED
Recovery    : OPEN → MANUAL_REQUIRED → CLOSED
ClosingRun  : RUNNING → COMPLETED | FAILED
```

- **BR-1**: 전이는 도메인 전이표(`canTransitionTo`)를 거치는 메서드로만 한다. 상태 필드 직접 대입 금지.
- **BR-2**: 종료 상태(DONE/COMPLETED/POSTED/CLOSED)의 금액은 **수정하지 않는다**. 조정 레코드 또는 역분개만이 정정 경로다.

### 7.2 등급 정책

| 등급      | 수수료율 | 기본 정산주기 | 홀드백     |
| --------- | -------- | ------------- | ---------- |
| NORMAL    | 3.50%    | T+7 영업일    | 30% / 30일 |
| VIP       | 2.50%    | T+3 영업일    | 10% / 14일 |
| STRATEGIC | 2.00%    | T+1 영업일    | 0% (즉시)  |

- **BR-3**: 셀러에 명시된 주기가 있으면 그것이 등급 기본값보다 우선한다.
- **BR-4**: 적용 요율은 정산 시점 스냅샷이며, 이후 정책이 바뀌어도 **과거 정산을 재계산하지 않는다**.
  요율 이견이 생기면 시뮬레이션 API 의 `source` 와 저장된 `commission_rate_source` 를 대조한다 — 다르면 과거 정산이 맞다.

### 7.3 금액 계산식

```
수수료       commission = 결제액 × 요율             (scale 2, HALF_UP)
순정산액     net        = 결제액 − commission
보류액       holdback   = net × 보류율
즉시지급분   immediate  = net − 미해제 holdback      (하한 0)

부가세       vat        = floor(commission × 10/110)      ← 포함과세
공급가액     supply     = commission − vat
원천징수     withhold   = 개인 ? floor(net × 0.033) : 0
실효원천징수 effective  = min(withhold, immediate)              ← 상계보다 먼저 확보(T-4)
회수상계     offset     = min(미상계 채권, immediate − effective)
실지급액     payout     = max(0, immediate − effective − offset)

환불 반영    net        = 결제액 − 누적환불액 − commission   (0 이하면 CANCELED)
대사 회수    net        = net − clawback                    (환불 누적치는 건드리지 않음)
```

- **BR-5**: 모든 금액은 `BigDecimal`. `double`/`float` 금지.
- **BR-6**: 누적 환불이 결제액을 초과하는 반영은 거부한다.
- **BR-7**: 대사 clawback 은 환불 누적치에 섞지 않는다(이중 계상·환불 delta 복원 오작동 방지).

### 7.4 원장 계정 체계

- **계정**: `ACCOUNTS_RECEIVABLE` · `ACCOUNTS_PAYABLE` · `REVENUE` · `COMMISSION_REVENUE` · `COMMISSION_EXPENSE` ·
  `SALES_REFUND` · `CASH` · `VAT_PAYABLE`
- **전표 유형**: `SETTLEMENT_CREATED` · `SETTLEMENT_CONFIRMED` · `REFUND_REVERSED` · `CHARGEBACK_REVERSED` ·
  `RECON_REVERSED` · `COMMISSION_RECOGNIZED` · `PAYOUT_EXECUTED` · `RECOVERY_RECOGNIZED` · `RECOVERY_OFFSET` · `VAT_ACCRUED`
- **참조 유형**: `SETTLEMENT` · `REFUND` · `CHARGEBACK` · `PG_RECONCILIATION` · `SELLER_RECOVERY` · `RECOVERY_OFFSET` · `SETTLEMENT_TAX`

## 8. 데이터 모델 (주요 테이블)

| 영역     | 테이블                                                                                                        |
| -------- | ------------------------------------------------------------------------------------------------------------- |
| 정산     | `settlements` · `settlement_adjustments` · `commission_rate_policy` · `settlement_loan_deductions` · `settlement_index_queue` |
| 지급     | `payouts` · `payout_bounces` · `seller_bank_accounts`                                                         |
| 원장     | `ledger_entries` · `ledger_outbox` · `ledger_periods`                                                         |
| 세무     | `tax_invoices` · `tax_invoice_scans` · `seller_tax_profiles`                                                  |
| 대사     | `pg_reconciliation_runs` · `pg_reconciliation_discrepancies`                                                  |
| 회수     | `seller_recoveries` · `recovery_allocations`                                                                  |
| 마감     | `monthly_closing_runs` · `seller_monthly_closings`                                                            |
| 프로젝션 | `settlement_order_view` · `settlement_payment_view` · `settlement_user_view` · `settlement_product_view`       |
| 이벤트   | `outbox_events` · `processed_events` · `quarantined_events` · `duplicate_events`                              |
| 운영     | `audit_logs`(파티셔닝) · `manual_operation_idempotency` · `shedlock`                                          |

DB 레벨 강제 장치: 불변성 트리거, 체크 제약, 원장 중복 전기 UNIQUE, 조정 출처 부분 UNIQUE,
요율 기간 중첩 차단(`EXCLUDE USING gist`), 감사로그 파티션 런웨이, 보존기간 정리 함수.

## 9. 인터페이스

### 9.1 REST (요약)

| 그룹        | 대표 경로                                                                         | 인가               |
| ----------- | --------------------------------------------------------------------------------- | ------------------ |
| 정산 조회   | `/settlements/**`, `/api/settlements/**`, `/api/settlements/query/**`              | ADMIN·MANAGER      |
| 원장·리포트 | `/api/ledger/**`, `/api/reports/**`                                               | ADMIN·MANAGER      |
| 셀러 셀프   | `/api/seller/bank-account`, `/api/tax-invoices/**`                                | ADMIN·MANAGER·USER |
| 지급 운영   | `/admin/payouts/**`, `/admin/backfill/**`                                         | ADMIN              |
| 정산 운영   | `/admin/settlements/**`, `/admin/commission-rates/**`                             | ADMIN              |
| 차지백      | `/admin/chargebacks/**`                                                           | ADMIN              |
| 이벤트 운영 | `/admin/dlq/**`, `/admin/outbox/**`, `/admin/event-track/**`                      | ADMIN              |
| 대사·정합성 | `/admin/pg-reconciliation/**`, `/admin/reconciliation/**`, `/admin/integrity/**`   | ADMIN·MANAGER      |
| 계좌·세무   | `/admin/seller-bank-accounts/**`, `/admin/seller-tax-profiles/**`, `/admin/tax/**` | ADMIN·MANAGER      |
| 회수        | `/admin/recoveries/**`                                                            | ADMIN·MANAGER      |
| 월마감      | `/admin/monthly-closing/**`, `/admin/ledger-periods/**`                           | ⚠ §12-B 참조       |
| 내부 대사   | `/internal/recon/settlements`                                                     | 내부 키 필터       |

### 9.2 이벤트

**소비** (단일 컨슈머 그룹, `processed_events` 로 멱등)

| 토픽                                           | 용도                                |
| ---------------------------------------------- | ----------------------------------- |
| `lemuel.payment.captured`                      | 정산 생성                           |
| `lemuel.payment.refunded`                      | ① 결제 프로젝션 갱신 ② 정산 조정    |
| `lemuel.order.created`                         | 주문 프로젝션                       |
| `lemuel.user.registered`                       | 셀러 프로젝션                       |
| `lemuel.product.changed`                       | 상품 프로젝션                       |
| `lemuel.seller.tier_changed`                   | 등급 변경 반영                      |
| `lemuel.loan.repayment_applied`                | 대출 상환 차감                      |
| `lemuel.pgreconciliation.discrepancy_approved` | 자기 발행 → 역정산 반영             |

**발행** (모두 Transactional Outbox 경유 — 직접 `kafkaTemplate` 발행 금지)

| aggregateType      | eventType                                                                                                                              |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| `Settlement`       | `SettlementCreated` · `SettlementConfirmed` · `SettlementHoldbackReleased` · `SettlementHoldbackConsumed` · `SettlementAdjusted` · `SettlementCanceled` · `SettlementWithholdingAccrued` |
| `Payout`           | `PayoutCompleted` (→ `lemuel.payout.completed`, account 구독)                                                                          |
| `seller_recovery`  | `Opened` · `Offset`                                                                                                                    |
| `PgReconciliation` | `PgReconciliationDiscrepancyApproved`                                                                                                  |

## 10. 비기능 요구

| ID     | 요구                                                                                        | 강제 지점                       |
| ------ | ------------------------------------------------------------------------------------------- | ------------------------------- |
| NFR-1  | **MSA 경계**: order-service 코드·DB 에 의존하지 않는다. 조회는 자체 프로젝션, 대사는 내부 API. | 빌드 의존성 부재 + ArchUnit + 저장소 가드 |
| NFR-2  | **헥사고날**: 도메인은 어댑터를 import 하지 않고 포트를 우회하지 않는다.                     | ArchUnit                        |
| NFR-3  | **멱등 3단**: 아웃박스 `event_id` UNIQUE → 컨슈머 `processed_events` PK → 도메인 UNIQUE.     | DB 제약                         |
| NFR-4  | **원자성**: 상태 변경과 이벤트 발행은 같은 트랜잭션에서 아웃박스로 커밋한다.                 | `SaveOutboxEventPort`           |
| NFR-5  | **금액 안전**: `BigDecimal` 강제, 라운딩 정책 보존.                                         | 저장소 가드 + 리뷰              |
| NFR-6  | **도메인 봉인**: public setter·`@Setter`/`@Data` 금지, 팩토리/rehydrate 전용.                | OO 게이트                       |
| NFR-7  | **인증**: JWT(HS256), `JWT_SECRET` 운영 필수(미설정 시 기동 실패).                           | shared-common                   |
| NFR-8  | **PII**: 계좌번호 암호화 저장·마스킹 노출, 감사로그 PII 가드.                                | `PAYOUT_ENC_KEY` · 마이그레이션 |
| NFR-9  | **롱 트랜잭션 억제**: 확정 배치는 청크 커밋으로 락 보유를 제한한다.                          | 청크 크기 100                   |
| NFR-10 | **분산 락**: 스케줄러 중복 실행 방지.                                                       | ShedLock                        |
| NFR-11 | **관측**: 정산 확정 건수·금액, 프로젝션 게이지, 컨슈머 메트릭.                               | Micrometer                      |
| NFR-12 | **커버리지**: JaCoCo LINE 90% + 핵심 도메인 INSTRUCTION 80%.                                 | Gradle 게이트                   |
| NFR-13 | **시각 표준**: 신규 코드는 `OffsetDateTime`(UTC)/`timestamptz`.                              | N1 래칫                         |

## 11. 배치·스케줄 (Asia/Seoul)

| 시각           | 작업                | 비고                     |
| -------------- | ------------------- | ------------------------ |
| 매월 1일 02:30 | 파티션 유지관리     | 감사로그 런웨이          |
| 03:00          | 정산 확정           | 정산일 도래분            |
| 03:00          | 홀드백 해제         | 해제일 도래분 → 지급 생성 |
| 03:30          | 회수채권 정체 이관  | → `MANUAL_REQUIRED`      |
| 04:00          | 지급 실행           | 한도 검사 후 펌뱅킹      |
| 매월 1일 04:30 | 정보계 월마감       | 전월 마트 적재           |
| 05:00          | 일일 대사           | order 교차 대사          |
| 06:00          | 정합성 모니터       | 8종 순회                 |
| 5초 간격       | 원장 아웃박스 폴러  | 로컬(Kafka 미경유)       |

## 12. 역산에서 드러난 격차

역산 과정에서 **문서·주석의 선언과 실제 코드가 다른 지점**이 발견됐다. 미화하지 않고 그대로 남긴다.

### A. 서브도메인 로스터 드리프트 (문서 문제) — ✅ 2026-08-12 해소 (T-2)

`../../../SPEC.md` §3.2 와 `../../../CLAUDE.md` 모듈 트리는 정산 코어를 7개 도메인(정산·지급·원장·차지백·PG대사·리포트·대사)으로 적고 있었으나,
실제 코드는 **12개**였다 — `tax`(세무), `closing`(월마감), `recovery`(회수채권), `integrity`(정합성), `idempotency`(멱등지원)가 문서에 없었다.
세무는 부가세·원천징수라는 **실제 현금흐름을 바꾸는** 기능이라 누락 영향이 컸다.

조치: `../../../SPEC.md` §3.2(세무·회수·월마감·정산운영 행 추가, 원장 행에 기간마감 보강) · `../../../CLAUDE.md` 모듈 트리 ·
`../../STRUCTURE.md` 도메인 목록을 현행화하고, 재발은 `harness-audit` 의 서브도메인 로스터 검사로 기계 차단한다
(코드 디렉터리 ↔ 문서 트리 줄 대조). 같은 작업에서 **모듈 로스터 검사가 `STRUCTURE.md` 를 루트 경로로 조회해
한 번도 검사되지 않던 결함**도 함께 고쳤다(`../../STRUCTURE.md`).

### B. 관리자 경로 2건의 인가 미적용 (코드 문제 — 검증 필요)

`ClosingAdminController`·`LedgerPeriodAdminController` 의 javadoc 은
"`/admin/**` → `hasRole("ADMIN")` 게이트를 상속한다"고 적었지만, shared-common `SecurityConfig` 에는
**`/admin/**` 포괄 매처가 존재하지 않는다.** 매처는 `/admin/payouts/**`, `/admin/settlements/**` 처럼 경로별로 열거되어 있고
그 목록에 `/admin/monthly-closing/**`·`/admin/ledger-periods/**` 는 **빠져 있다**. 두 경로는 최종 규칙
`anyRequest().authenticated()` 로 떨어진다. 컨트롤러에도 `@PreAuthorize` 는 없다(모듈 전체 0건).

> **결과(정적 분석 기준)**: 일반 `USER` 토큰만 있으면 `POST /admin/monthly-closing/{ym}/run`(월마감 실행)과
> `POST /admin/ledger-periods/{ym}/close`(원장 기간 마감·잠금)를 호출할 수 있다. 두 조작 모두 되돌리기 어렵다
> (기간 마감은 재개봉 없음).
>
> 이 항목은 **코드 정적 분석 결과이며 런타임으로 검증하지 않았다.** 조치 전 통합테스트(USER 토큰 → 403 기대)로 먼저 확인할 것.

### C. 코드가 스스로 밝힌 알려진 한계

- ~~**대사 clawback 의 원장 역분개 미반영**~~ — **오독이었다(2026-08-12 정정).** `Settlement.applyReconciliationClawback` 과
  `ApplyReconciliationAdjustmentService` 의 주석이 "원장 역분개는 후속 과제"라고 적고 있었으나, **코드는 이미 연동돼 있다**:
  조정마다 `enqueueReverseReconciliation` → `REVERSE_RECONCILIATION` 태스크 → 폴러 → `RECON_REVERSED` 전표 2행
  (`Dr SALES_REFUND / Cr ACCOUNTS_PAYABLE` + `Cr COMMISSION_REVENUE`). DB 제약(`V20260722120000`)·단위테스트·
  통합 IT(재실행에도 정확히 2건, INV-5 누락 감지)까지 갖춰져 있다(구현 커밋 `3ca0ec4af`). 낡은 주석은 제거했다.
- ~~**원천징수 캡핑 잔여분**~~ — **2026-08-12 해소(T-4).** 원인은 차감 *순서*였다(회수상계 먼저 → 세금 재원 잠식).
  순서를 `원천징수 → 채권상계` 로 바꾸고, 상계에는 원천징수를 뗀 잔여만 가용액으로 넘긴다. 현행 등급 정책
  (최대 홀드백 30%)에서 `immediate ≥ 0.7×net > 0.033×net` 이므로 **과소징수는 구조적으로 발생하지 않는다**.
  못 상계된 채권은 `OPEN` 으로 남아 다음 정산에서 회수된다(이월 경로 기존 구현). 잔여 클램프가 걸리는
  극단은 `settlement.withholding.shortfall` 메트릭으로 관측한다(정상 0).
- **세무 프로필 미등록 = 사업자 취급** — 원천징수 0 으로 지급된다. 개인 셀러가 프로필을 안 올리면 과세 리스크가 사업자 쪽으로 기운다.
- **레거시 수수료 상수 3%** — `Settlement.COMMISSION_RATE` 는 이력 보존용이며 신규 경로가 참조하면 안 된다.

## 13. 추적 항목

| #   | 항목                        | 제안 조치                                                                     |
| --- | --------------------------- | ----------------------------------------------------------------------------- |
| T-1 | §12-B 인가 누락             | 🔶 진행 중 — RED 실증(USER·MANAGER → 500, 인가 미차단) + `SecurityConfig` 매처 2건 추가 완료. GREEN 은 병행 FEP 작업의 컴파일 오류로 대기 |
| T-2 | §12-A 문서 드리프트         | ✅ 2026-08-12 완료 — 문서 3종 현행화 + `harness-audit` 서브도메인 로스터 검사 신설(테스트 179개 GREEN) |
| T-3 | 대사 clawback 원장 역분개   | ✅ 해당 없음 — 이미 구현·테스트 완료였고 주석만 낡아 있었다(2026-08-12 주석 정정). 설계 불필요 |
| T-4 | 원천징수 미징수 잔여분      | ✅ 2026-08-12 완료 — 이월/채권화 대신 **차감 순서 확정**(원천징수 우선)으로 원인 제거. 정본은 `settlement-domain-rules` 스킬 |
