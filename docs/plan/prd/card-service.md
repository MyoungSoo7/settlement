# PRD — 법인카드 (card-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`settlement-core.md`](settlement-core.md) 와 같은 규약을 쓴다 — 새 기능을 제안하지 않고,
> 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                          |
> | --------- | ------------------------------------------------------------------------------------------- |
> | 대상 범위 | `card-service`(8106, mgmt 8107, DB `lemuel_card`) 전체 — Phase 1(발급·한도) + Phase 2(승인·매입·명세서·지출관리) |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                                 |
> | 근거      | 도메인 21개 클래스, 진입 어댑터 8종, 유스케이스 서비스 21개, Kafka 컨슈머 6종, 스케줄러 4종, Flyway V2~V9, 테스트 45개 클래스 |
> | 범위 밖   | 카드 실물 발급·VAN 실연동·회계 전표(account-service GL) — §2.2                              |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) §3.14 · `card-service-rules` 스킬(강제 규칙 정본) · ADR 0022·0030 |

---

## 1. 배경과 문제

플랫폼에는 이미 **셀러에게 지급할 확정 정산금**이 쌓여 있다. 셀러 입장에서 그 돈은 정산일이 와야 쓸 수 있는
묶인 자산이고, 그 사이 임직원의 사업 경비는 개인 카드로 결제된 뒤 영수증으로 정산된다. 이 간극이 세 가지 문제를 만든다.

| 문제            | 구체적 손상                                                                 |
| --------------- | --------------------------------------------------------------------------- |
| **자금 회전**   | 받을 돈이 있는데도 지출은 개인 신용에 의존 — 사업 확장이 정산 주기에 묶인다 |
| **통제 부재**   | 임직원별 한도·업종 제한이 없어 지출이 사후에야 드러난다                     |
| **여신 리스크** | 한도를 넉넉히 주면 회수 불능, 짜게 주면 카드가 무용지물                     |

card-service 는 **셀러의 미지급 정산금을 담보로 법인카드 한도를 산정**하고, 그 한도를 임직원별 서브한도로
쪼개 실시간 승인·매입·월 청구·지출 증빙까지 잇는다. 핵심 설계 판단은 하나다 — **재원의 정답지를 스스로 갖지 않는다.**

## 2. 목표 / 비목표

### 2.1 목표

- **G1 — 재원 기반 자동 여신**: 셀러 조직이 생기면 정산 재원과 평판 등급으로 마스터 한도를 자동 산정한다.
- **G2 — 한도 불변식 보장**: `마스터 한도 ≥ Σ 활성 서브한도` 를 어떤 동시 요청에서도 깨뜨리지 않는다.
- **G3 — 실시간 승인**: VAN 승인 요청에 한도·상태·가맹점 정책을 검사해 즉시 승인/거절하고 홀드를 잡는다.
- **G4 — 청구 사이클 완결**: 매입 → 월 명세서 마감 → 납부 → 연체까지 상태로 관리한다.
- **G5 — 지출 관리 SaaS**: 매입 이벤트로 경비보고서를 자동 생성하고 제출·승인·반려 워크플로를 제공한다.
- **G6 — 승인 경로 보호**: 지출관리 기능이 승인 지연(p99)을 오염시키지 않는다.

### 2.2 비목표 (명시적 경계)

- **재원을 소유하지 않는다.** `sellerPayable`·`holdbackPayable` 의 정답지는 account-service GL 통제계정이며
  card 는 매 산정마다 조회한다(ADR 0030). 복제·캐시하지 않는다.
- **실물 카드·실제 VAN 연동은 없다.** `/van/v1/**` 는 외부 VAN 프로토콜을 모방한 시뮬레이터 진입점이다.
- **회계 전표를 쓰지 않는다.** 카드 사건은 이벤트로 내보내고 GL 인식은 account-service 몫이다.
- **예산 초과를 승인 단계에서 막지 않는다.** 부서 예산은 집계·증빙용이다 — 지출 *후* 관리 SaaS다.
- **강등된 계정을 자동 복구하지 않는다.** 재원이 회복돼도 복귀는 사람의 결정이다.

## 3. 사용자

| 사용자           | 역할          | 관심사                             | 인터페이스                                                                |
| ---------------- | ------------- | ---------------------------------- | ------------------------------------------------------------------------- |
| 셀러 대표(OWNER) | 조직 소유자   | 마스터 한도, 임직원 카드 발급·정지 | `POST /api/cards/accounts`, `/accounts/{id}/cards`                        |
| 관리자(MANAGER)  | 조직 관리자   | 서브한도 조정, 경비 승인·반려      | `PATCH /api/cards/cards/{id}/limit`, `/internal/api/v1/expense-reports/**` |
| 임직원(STAFF)    | 카드 소지자   | 내 카드·한도 조회, 경비 제출       | `GET /api/cards/cards/me`                                                 |
| VAN(외부 망)     | 결제 네트워크 | 승인·매입·취소·환불 전문           | `POST /van/v1/{authorizations,captures,voids,refunds}`                    |
| 내부 서비스      | 시스템        | 승인 API, 명세서 납부              | `/internal/api/v1/**`                                                     |

## 4. 제품 범위 — 기능 맵

단일 바운디드 컨텍스트(`card`) 안에서 5개 기능군으로 구성된다.

| #   | 기능군    | 책임                                                          | 진입점                         |
| --- | --------- | ------------------------------------------------------------- | ------------------------------ |
| 1   | 계정·한도 | 카드계정 개설(심사), 마스터 한도 산정·일 1회 재산정·강등      | REST + 배치 + Kafka(조직·평판) |
| 2   | 카드 발급 | 임직원 카드 발급, 서브한도 변경, 정지·해지                    | REST + Kafka(멤버십)           |
| 3   | 승인      | 실시간 한도·상태·가맹점 정책 검사, 홀드 생성/만료             | VAN + 내부 API + 배치          |
| 4   | 매입·청구 | 매입·부분매입·취소·환불, 월 명세서 마감·납부·연체             | VAN + 내부 API + 배치 3종      |
| 5   | 지출관리  | 매입 이벤트 → 경비보고서 자동 생성, 제출·승인·반려, 부서 예산 | Kafka + 내부 API               |

## 5. 핵심 유스케이스

### UC-1. 카드계정 개설 → 한도 산정

```
organization.created ──Kafka──► 조직 프로젝션 적재
셀러 대표: POST /api/cards/accounts
  ① account-service 내부 API 로 재원 조회 (X-Internal-Api-Key)
     F = sellerPayable + holdbackPayable        ← 부호 있는 원본 보존, 합계에만 하한 적용
  ② 평판 등급 조회(프로젝션) → haircut H
  ③ masterLimit = floor(F × R × H)              R = 0.70(설정), 원 단위 절사
  ④ 최소 한도 미달(300,000) 또는 E등급 → 계정 SUSPENDED (한도 0 이 아니라 상태로 표현)
```

재원 조회가 실패하면 **503 으로 끝난다** — 기본값·캐시·직전 한도 같은 폴백은 두지 않는다.

### UC-2. 임직원 카드 발급 (한도 불변식)

```
POST /api/cards/accounts/{id}/cards
  ① LoadCardAccountPort.findByIdForUpdate(id)      ← 계정 행 비관적 락 (먼저!)
  ② LoadCardPort.sumActiveSubLimits(accountId)     ← 그 다음 합계 재계산
  ③ master ≥ Σsub + 신규 검증 → 저장 → 이벤트      ← 같은 트랜잭션
```

순서를 뒤집거나 락을 생략하면 "합계를 읽은 뒤 반영하기 전" 창으로 동시 발급이 통과한다.

### UC-3. 실시간 승인

```
VAN → POST /van/v1/authorizations
  networkRequestId 로 기존 홀드 선조회(멱등) → 카드/계정 상태 → 가맹점 정책 → 가용한도
  가용한도 = masterLimit − Σ ACTIVE 홀드 − Σ 매입
  거절 사유는 4종뿐: LIMIT_EXCEEDED · CARD_SUSPENDED · MEMBER_INACTIVE · MERCHANT_POLICY_VIOLATION
```

### UC-4. 매입 → 명세서 → 납부 → 연체

```
매입: 홀드 소진 + CardCapture 생성 (부분 매입 시 홀드 PARTIALLY_CAPTURED, 잔여 유지)
마감: 매월 1일 01:00 → OPEN → CLOSED
납부: POST /internal/api/v1/statements/{id}/payments (paymentId 멱등)
      전액 납부 → PAID + lemuel.card.statement_paid 발행, DELINQUENT 계정은 ACTIVE 복구
연체: 매일 02:00 → CLOSED/PARTIALLY_PAID → DELINQUENT (명세서 1건 = 트랜잭션 1건)
```

### UC-5. 지출관리 (승인 경로와 분리)

매입은 `lemuel.card.captured` 를 발행하고, 컨슈머가 **사후에** 경비보고서를 만든다.
승인 서비스는 경비 도메인을 참조하지 않으며 이는 ArchUnit + 지연 테스트로 기계 강제된다.

## 6. 기능 요구사항

### 6.1 계정·한도 (FR-A)

| ID    | 요구                                                                                               | 근거                                                   |
| ----- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| FR-A1 | 마스터 한도 = `floor((sellerPayable + holdbackPayable) × R × H)`. R 기본 0.70, H 는 등급별 haircut. | `CardLimitPolicy`                                      |
| FR-A2 | 재원 스냅샷은 **부호 있는 원본값**을 보존하고 하한은 합계 F 에만 적용한다(항별 0 바닥 금지).        | `CardLimitPolicy` · `LimitSnapshot`                    |
| FR-A3 | 재원 조회 실패는 폴백 없이 **503**(`CARD_FUNDING_UNAVAILABLE`)으로 끝낸다.                          | `AccountFundingAdapter` · `FundingUnavailableException` |
| FR-A4 | 최소 한도(300,000) 미달·E등급 탈락은 한도 0 이 아니라 **계정 SUSPENDED** 로 표현한다.               | `ScreeningResult` · `OpenCardAccountService`            |
| FR-A5 | 마스터 한도 하향은 Σ서브한도에서 멈추고(클램프), 클램프 사실을 이벤트 페이로드에 싣는다.            | `CardAccount.changeMasterLimit` · `LimitChangeResult`   |
| FR-A6 | 매일 03:30(KST) ACTIVE 계정만 재산정한다. 계정 1건 = 트랜잭션 1건, 무변경이면 저장·발행 생략.       | `CardLimitRecalculationScheduler` · `CardAccountRescreener` |
| FR-A7 | 강등된 계정은 자동 복구하지 않는다(사람이 `resume`).                                                | `RecalculateCardLimitsService`                          |

### 6.2 카드 발급·상태 (FR-C)

| ID    | 요구                                                                                              | 근거                                         |
| ----- | ------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| FR-C1 | 발급·한도변경은 계정 행 비관적 락 → 서브한도 합계 재계산 → 검증 순서를 지킨다.                     | `IssueCardService` · `ChangeSubLimitService` |
| FR-C2 | `sumActiveSubLimits` 는 `status <> CANCELED` — **정지 카드도 한도를 계속 점유**한다.               | `LoadCardPort`                               |
| FR-C3 | 카드 상태는 `ISSUED ⇄ SUSPENDED → CANCELED`(터미널). `CANCELED → SUSPENDED` 는 금지 전이다.        | `CardStatus` · `Card`                        |
| FR-C4 | 조직 이탈 처리는 `findActiveByHolder`(CANCELED 제외)로 조회해 해지 카드에서 예외가 나지 않게 한다. | `OrgProjectionService`                       |

### 6.3 승인 (FR-Z)

| ID    | 요구                                                                                             | 근거                   |
| ----- | ------------------------------------------------------------------------------------------------ | ---------------------- |
| FR-Z1 | 가용한도 = `masterLimit − Σ ACTIVE 홀드 − Σ 매입`. 검증 전 계정 행을 비관적 락으로 잠근다.        | `AuthorizeCardService` |
| FR-Z2 | `authorizationId`(VAN 멱등키) 재전송은 새 홀드를 만들지 않고 기존 홀드를 반환한다.                | `AuthorizeCardService` |
| FR-Z3 | 거절 사유는 **4종 고정**이며 추가는 ADR 0022 파괴적 변경이다. DELINQUENT 계정은 `CARD_SUSPENDED`. | `DeclineReason`        |
| FR-Z4 | 가맹점 정책은 MCC 허용/차단, 1회·일·월 한도, 해외·온라인 여부를 계정 또는 카드 단위로 건다.       | `MerchantPolicy`       |
| FR-Z5 | 홀드는 `ACTIVE → CAPTURED / PARTIALLY_CAPTURED / VOIDED / EXPIRED / REFUNDED` 로만 전이한다.      | `HoldStatus`           |
| FR-Z6 | 만료 배치는 매일 04:00, ShedLock 으로 중복 실행을 막는다.                                         | `HoldExpiryScheduler`  |

### 6.4 매입·청구 (FR-B)

| ID    | 요구                                                                                    | 근거                                    |
| ----- | --------------------------------------------------------------------------------------- | --------------------------------------- |
| FR-B1 | 매입은 홀드를 소진하고 `CardCapture` 를 만든다. 부분 매입은 잔여 홀드를 유지한다.        | `CaptureHoldService`                    |
| FR-B2 | 취소(Void)·환불(Refund)은 마스터 한도를 원복하고 각각 이벤트를 남긴다(`refundId` 멱등).  | `VoidHoldService` · `RefundHoldService` |
| FR-B3 | 명세서는 매월 1일 01:00 마감(OPEN → CLOSED)한다.                                        | `StatementBillingScheduler`             |
| FR-B4 | 납부는 `paymentId` UNIQUE 로 멱등하며, 전액 납부 시 PAID + 이벤트 발행 + 계정 복구.      | `PayStatementService`                   |
| FR-B5 | 연체 배치는 매일 02:00, **명세서 1건 = 트랜잭션 1건**(`REQUIRES_NEW`).                   | `DelinquentStatementProcessor`          |

### 6.5 지출관리 (FR-E)

| ID    | 요구                                                                                    | 근거                                                        |
| ----- | --------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| FR-E1 | 경비보고서는 `lemuel.card.captured` 소비로 생성한다(`captureId` UNIQUE 멱등).            | `CardCapturedExpenseConsumer`                               |
| FR-E2 | 상태는 `DRAFT → SUBMITTED → APPROVED / REJECTED`, 반려분은 재제출할 수 있다.             | `ExpenseReportStatus`                                       |
| FR-E3 | 승인 서비스는 경비 도메인을 참조하지 않는다 — ArchUnit + p99 ≤ 300ms 지연 테스트로 강제. | `ExpenseWorkflowDecouplingTest` · `AuthorizationLatencyTest` |
| FR-E4 | 부서 예산은 월 총액·소진액 집계만 제공하고 승인 거절 사유가 되지 않는다.                 | `DepartmentBudget`                                          |

## 7. 도메인 규칙 (BR)

### 7.1 상태머신

```
CardAccount  : SCREENING → ACTIVE ⇄ SUSPENDED / DELINQUENT → CLOSED   (심사 탈락 → REJECTED)
Card         : ISSUED ⇄ SUSPENDED → CANCELED(터미널)                   (CANCELED → SUSPENDED 금지)
Hold         : ACTIVE → CAPTURED | PARTIALLY_CAPTURED | VOIDED | EXPIRED | REFUNDED
Statement    : OPEN → CLOSED → {PARTIALLY_PAID, PAID, DELINQUENT} → PAID
ExpenseReport: DRAFT → SUBMITTED → APPROVED | REJECTED → SUBMITTED(재제출)
```

- **BR-1**: 한도 불변식 `masterLimit ≥ Σ sumActiveSubLimits` 는 DB CHECK 로 표현할 수 없다
  (CardAccount 와 Card 는 다른 애그리거트, Postgres CHECK 는 타 테이블 집계 불가). **락 순서가 유일한 방어**다.
- **BR-2**: 정지는 한도를 반환하지 않는다. 해지(CANCELED)만 반환한다 — 복직자가 돌아갈 한도를 남기기 위해서다.
- **BR-3**: 금액은 전부 `BigDecimal`, 이벤트 JSON 에서는 `toPlainString()`(DATA-STANDARD N5).
- **BR-4**: 재산정은 정산 확정 배치(03:00) 이후인 **03:30** 에 돈다. 겹치면 확정 도중 재원으로 산정된다.

### 7.2 한도 산정식

```
F = sellerPayable + holdbackPayable        (account-service GL 통제계정 조회, 부호 보존)
R = 인정비율 (기본 0.70)
H = 평판 haircut (A·B 1.00 / C 0.85 / D 0.70 / E 0.00)
masterLimit = floor(F × R × H)             (원 단위 FLOOR — 반올림으로 1원도 더 주지 않는다)
```

**R 이 1 이 아닌 이유**: F 는 곧 셀러에게 지급될 돈이라 카드 이용과 정산 지급이 **같은 재원을 두 번** 쓸 수 있다.
실제 상계는 청구 사이클의 몫이고, 그때까지 R 이 그 위험을 흡수한다 — §12-A 의 미해소 한계.

## 8. 데이터 모델

| 영역      | 테이블                                                                    |
| --------- | ------------------------------------------------------------------------- |
| 계정·카드 | `card_accounts` · `cards`                                                 |
| 승인·매입 | `authorization_holds` · `card_captures` · `merchant_policies`             |
| 청구      | `card_statements` · `statement_payments`                                  |
| 지출관리  | `expense_reports` · `department_budgets`                                  |
| 프로젝션  | `org_projection` · `org_member_projection` · `reputation_projection`      |
| 공통      | `outbox_events` · `processed_events` · `audit_logs`(파티셔닝) · `shedlock` |

## 9. 인터페이스

### 9.1 REST

| 경로군                      | 노출                         | 인가                                           |
| --------------------------- | ---------------------------- | ---------------------------------------------- |
| `/api/cards/**` (8개)       | gateway 라우팅 O             | `authenticated()` + 서비스 내 소유권·역할 판정 |
| `/van/v1/**` (4개)          | gateway 라우팅 **X**(내부망) | `authenticated()` — §12-B 참조                 |
| `/internal/api/v1/**` (4개) | gateway 라우팅 **X**         | `permitAll` + `InternalApiKeyFilter`           |
| `/admin/expense-receipts` (2개) | gateway 라우팅 O         | `/admin/**` ADMIN 게이트 — 영수증 리뷰 큐(ADR 0036) |

`/admin/expense-receipts` 는 OCR 판정이 `NEEDS_REVIEW` 로 흘린 영수증을 사람이 종결하는 큐다 —
`GET /` 로 큐를 읽고 `POST /{receiptId}/review` 로 판정한다. **OCR 은 사람의 판단을 대체하지 않는다**는
ADR 0036 무폴백 설계의 사람 쪽 절반이며, 이 경로가 없으면 저신뢰 추출 건이 갈 곳을 잃는다.

### 9.2 이벤트

**소비** (모두 `app.kafka.enabled=true` 조건부 — 기본값은 `false`)

| 토픽                                                     | 용도                        |
| -------------------------------------------------------- | --------------------------- |
| `lemuel.organization.created`                            | 조직 프로젝션 + 계정 후보   |
| `lemuel.organization.member_joined/removed/role_changed` | 멤버 프로젝션·카드 정지     |
| `lemuel.company.reputation_changed`                      | 평판 등급 프로젝션          |
| `lemuel.card.captured`                                   | (자기 발행) 경비보고서 생성 |

**발행** (전부 Outbox 경유, aggregateType=`Card`, **파티션 키는 언제나 cardAccountId**)

`CardAccountOpened` · `CardIssued` · `CardLimitChanged`(scope=MASTER/SUB) · `CardStatusChanged` ·
`CardAccountStatusChanged` · `CardAuthorized` · `CardCaptured` · `CardStatementPaid`

## 10. 비기능 요구

| ID    | 요구                                                          | 강제 지점                                                     |
| ----- | ------------------------------------------------------------- | ------------------------------------------------------------- |
| NFR-1 | 승인 지연 p99 ≤ 300ms, 승인 경로에 지출관리 의존 0            | `AuthorizationLatencyTest` + ArchUnit                         |
| NFR-2 | 동시 발급·동시 승인에서 한도 불변식 유지                      | `CardIssuanceLimitConcurrencyIT` · `ConcurrentAuthorizationIT` |
| NFR-3 | 모든 배치는 ShedLock, 항목 1건 = 트랜잭션 1건(`REQUIRES_NEW`) | 스케줄러 4종 + Processor 빈                                   |
| NFR-4 | 이벤트는 Outbox 경유(직접 발행 금지), 멱등 3단                | `CardEventPublisherAdapter`                                   |
| NFR-5 | 재원은 복제하지 않고 매 산정 조회(ADR 0030)                   | `AccountFundingAdapter`                                       |
| NFR-6 | 금액 `BigDecimal`, 이벤트 직렬화는 문자열                     | DATA-STANDARD N5                                              |
| NFR-7 | 커버리지 JaCoCo LINE 90%                                      | Gradle 게이트                                                 |

## 11. 배치 (Asia/Seoul)

| 시각           | 작업        | 잠금                               |
| -------------- | ----------- | ---------------------------------- |
| 매월 1일 01:00 | 명세서 마감 | `card-statement-billing` (PT30M)   |
| 매일 02:00     | 연체 전이   | `card-delinquency-batch` (PT30M)   |
| 매일 03:30     | 한도 재산정 | `card-limit-recalculation` (PT30M) |
| 매일 04:00     | 홀드 만료   | `card-hold-expiry` (PT30M)         |

## 12. 역산에서 드러난 격차

### A. 재원 이중 사용이 아직 해소되지 않았다 (설계상 미해결 — 최상위 리스크)

같은 정산금 F 가 **카드 한도**와 **정산 지급** 양쪽의 담보로 쓰인다. 셀러가 한도를 다 쓰고 정산금도 전액
지급받으면 회수 재원이 사라진다. 현재는 `R = 0.70` 이 그 위험을 확률적으로 흡수할 뿐이고, 실제 상계는
구현되어 있지 않다. `card-service-rules` 스킬이 이 한계를 명문화하고 있다("3단계 담당자는 이 R 을 상계
구현과 함께 재조정해야 한다"). 한편 `deposit-service` 는 "hold/offset 으로 재원 이중사용 차단"을 표방하며
존재하지만 **card 와 연결되어 있지 않다**. → Seed `card-service-funding-offset` 참조.

### B. `/van/**` 경로에 전용 게이트가 없다 (방어 비대칭) — ✅ 2026-08-13 해소 (C-2)

> **조치 완료**: `/van/**` 을 `InternalApiKeyFilter` 보호 대상에 편입하고 `SecurityConfig` 에
> `permitAll`(필터가 게이트)로 명시했다. VAN 은 사람이 아니라 기계라 역할이 아니라 공유 시크릿으로 가린다.
> 회귀 가드 `VanPathGateTest` 는 사용자·ADMIN 토큰 단독 401 / 올바른 시크릿 통과를 검증한다.
> 조치 과정에서 **필터가 `getServletPath()` 만 보고 있어 그 값이 비면 게이트가 조용히 꺼지는** 잠재 구멍도
> 함께 막았다(requestURI 폴백 + 컨텍스트 경로 제거). 이는 기존 `/internal/**` 보호에도 있던 구멍이다.
> 아래는 해소 전 상태 기록이다.

`/van/v1/{authorizations,captures,voids,refunds}` 는 shared-common `SecurityConfig` 의 경로별 매처 목록에
없어 최종 규칙 `anyRequest().authenticated()` 로 떨어진다. 즉 **유효한 사용자 JWT 만 있으면 누구나** 승인·매입·
취소·환불 전문을 밀어넣을 수 있다.

- **완화 통제(확인됨)**: gateway 는 `/api/cards/**` 만 라우팅하므로 외부에서 이 경로에 닿지 않는다.
  어댑터 javadoc 의 "운영에서 API Gateway 가 노출하지 않는다"는 서술은 사실이다.
- **그래도 비대칭이다**: 같은 성격의 내부 경로 `/internal/**` 은 `InternalApiKeyFilter` 로 한 겹 더 막는데
  `/van/**` 에는 그 겹이 없다. NodePort 직노출·클러스터 내부 이동 시 사용자 토큰 하나로 카드 거래를 위조할 수 있다.
- **정적 분석 결과이며 런타임 미검증** — 조치 전 통합테스트(USER 토큰 → 403 기대)로 먼저 확인할 것.

### C. Kafka 소비가 기본값으로 꺼져 있다 — ✅ 2026-08-13 해소 (C-3, 다만 원인 진단은 정정)

`app.kafka.enabled` 기본값이 `false` 라 컨슈머 6종이 전부 `@ConditionalOnProperty` 로 비활성이다.
`docker-compose` 는 `APP_KAFKA_ENABLED=true` 를 주입하므로 통합 환경은 정상이지만, 서비스만 단독 기동하면
조직·멤버십·평판 프로젝션이 **조용히 갱신되지 않는다**(에러 없이 빈 프로젝션).

> **정정(2026-08-13)**: 이 문서는 처음에 이를 card 고유의 설정 실수처럼 적었으나, 확인해 보니
> **코어 11개 서비스가 모두 `APP_KAFKA_ENABLED:false`** 다 — 브로커 없이 서비스를 단독 기동할 수 있게 하는
> 의도된 플랫폼 규약이다. 따라서 기본값을 뒤집는 것은 오답이고(로컬 기동 관례를 깬다), 고쳐야 할 것은
> **침묵**이다.
>
> **조치**: `KafkaConsumptionStartupNotice` 가 기동 시 비활성이면 무엇이 멈추는지(프로젝션·경비보고서 자동
> 생성)와 조치법(`APP_KAFKA_ENABLED=true`)을 WARN 으로 남긴다. 활성이면 INFO 한 줄로 끝내 정상 상태를
> 경고로 오염시키지 않는다. 회귀 가드는 `KafkaConsumptionStartupNoticeTest`(로그 캡처).
> 같은 침묵이 나머지 10개 서비스에도 있으므로 shared-common 으로 일반화하는 것은 후속 과제로 남긴다
> (이번엔 PRD 대상 서비스 범위만 손댔다).

### D. 설정의 발행 토픽 목록이 실제보다 짧다 — ✅ 2026-08-13 해소 (C-4)

`application.yml` 의 `app.kafka.topic.card-*` 항목은 4개(account_opened·issued·limit_changed·status_changed)뿐이지만
실제 발행은 8종이었다. 토픽명은 `aggregateType + eventType` 에서 파생되므로 동작에는 영향이 없으나,
설정만 읽고 계약 범위를 판단하면 절반을 놓친다.

> **조치**: 8종을 마저 적는 대신 4개를 **제거**했다 — 그 키들은 어떤 코드도 읽지 않는(`KafkaOutboxPublisher`
> 가 파생) 죽은 설정이라, 8개로 늘리면 오해의 소지만 두 배가 된다. 대신 파생 규칙과 계약 정본 위치를
> 주석으로 남겼다. 수신 키는 `@KafkaListener` 가 실제로 읽으므로 유지한다.
>
> **함께 메운 구멍**: 계약 테스트가 8종 중 6종만 실제 발행 페이로드와 대조하고 있었다.
> `authorized`·`captured` 는 정본 샘플만 검증됐는데(그 테스트는 "1단계엔 발행 코드가 없다"는 전제로
> 작성됐고 Phase 2 구현으로 그 전제가 낡았다), 실제 페이로드 대조 3건을 추가해 8종을 모두 덮었다.
> 확인 결과 **실제 드리프트는 없었다** — 비어 있던 것은 커버리지였다.

### E. 청구 사이클에 입력이 없었다 (이 PRD 자체의 오류) — ✅ 2026-08-13 해소 (C-5)

§6.4 는 매입 → 명세서 마감 → 납부 → 연체를 구현된 기능으로 기술했지만, 확인해 보니 **명세서를 여는
경로도 매입액을 쌓는 경로도 없었다**: `OpenCardStatementUseCase` 와 `CardStatement.addCharge` 의 호출자가
각각 0 이었다. `CloseStatementService` 는 열린 명세서를 닫을 뿐 열지 않고, `CaptureHoldService` 는 명세서를
전혀 건드리지 않는다. 명세서를 만드는 것은 통합테스트 2개뿐이었다 — 테스트가 시작점을 대신 만들어 주니
마감·납부·연체 배치가 모두 초록불이었고, 그래서 이 PRD 도 검증 없이 "구현됨"으로 적었다.

> **조치**: `CardCapturedStatementConsumer` 가 `lemuel.card.captured` 를 별도 컨슈머 그룹으로 소비해
> `ChargeCardStatementUseCase`(주기 명세서 개시 멱등 + 청구 적재)를 호출한다. 매입 서비스에서 직접 부르지
> 않은 것은 지출관리와 같은 비결합 원칙이고, 그룹이 달라 서로의 실패에 영향받지 않는다. 청구주기는
> **KST 연월**로 고정했다 — UTC 파드에서 월말 자정 매입이 전월로 잡히면 이미 마감된 명세서에 붙는다.
> 만기일은 익월 10일(마감 01:00 이후)이다.
>
> **재발 방지**: `InboundPortReachabilityTest`(shared-common 공용 조건)를 10개 서비스에 확산했다.
> 이 유형은 단위 테스트가 구조적으로 볼 수 없다.

## 13. 추적 항목

| #   | 항목                     | 제안 조치                                                              |
| --- | ------------------------ | ---------------------------------------------------------------------- |
| C-1 | §12-A 재원 이중 사용     | Seed `card-service-funding-offset` — deposit hold/offset 연계 설계·구현 |
| C-2 | §12-B `/van/**` 게이트   | ✅ 2026-08-13 완료 — 공유 시크릿 게이트 편입 + servletPath 폴백 구멍 동반 수정(card 343·shared 275 GREEN) |
| C-3 | §12-C Kafka 기본값       | ✅ 2026-08-13 완료 — 기본값은 플랫폼 규약이라 유지하고 기동 경고로 침묵을 제거(card 345 GREEN). 11개 서비스 일반화는 후속 |
| C-4 | §12-D 설정·코드 드리프트 | ✅ 2026-08-13 완료 — 죽은 발행 키 4개 제거 + 계약 테스트 6→8종 확대(card 348 GREEN) |
| C-5 | §12-E 청구 사이클 입력 부재 | ✅ 2026-08-13 완료 — 매입 이벤트 → 명세서 개시·청구 적재 배선 + 도달성 가드 10개 서비스 확산(card 349 GREEN) |
