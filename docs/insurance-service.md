# insurance-service 설계 문서

> GA(법인보험대리점) 플랫폼 — 설계사(FC)가 매일 쓰는 업무 시스템.
> 상담 → 청약 → 계약 → 유지·변경 → 수수료 정산을 하나로 잇는다.
> V6+ 방카슈랑스 확장: 판매채널(FC/BANCA)·대면 상품설명서 교부 증빙·25%룰 모니터링·배치 5종.

## 1. 도메인 모델

```
Consultation (상담)
  consultationId  : UUID PK
  fcId            : VARCHAR   -- 배정된 설계사
  status          : ENUM(NEW, ASSIGNED, IN_PROGRESS, COMPLETED, LOST)
  createdAt       : TIMESTAMPTZ

InsuranceProduct (보험상품 카탈로그)
  productId       : UUID PK
  productCode     : VARCHAR UNIQUE
  productName     : VARCHAR
  productType     : VARCHAR   -- LIFE, ACCIDENT, HEALTH, ...
  annualPremium   : NUMERIC
  coverageAmount  : NUMERIC
  commissionRateY1: NUMERIC   -- 초년도 수수료율
  commissionRateY2: NUMERIC   -- 차년도 수수료율
  active          : BOOLEAN

InsuranceApplication (청약)
  applicationId   : UUID PK
  consultationId  : UUID FK → Consultation
  productId       : UUID FK → InsuranceProduct
  status          : ENUM(SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED)
  insuredPersonId : UUID FK → InsuredPerson (별도 테이블, PII 분리)
  contractorId    : UUID FK → Contractor   (별도 테이블, PII 분리)

Policy (계약 — System of Record, D1)
  policyId        : UUID PK
  applicationId   : UUID FK → InsuranceApplication
  policyNumber    : VARCHAR UNIQUE   -- 증권번호, 도메인 자연키
  status          : ENUM(ACTIVE, LAPSED, SURRENDERED, EXPIRED, CANCELLED)
  effectiveDate   : DATE
  maturityDate    : DATE
  lapsedAt        : DATE (nullable)  -- 실효일, 부활 기산점
  consecutivePremiumFailures : INT DEFAULT 0
  premiumAmount   : NUMERIC
  paymentCycle    : ENUM(MONTHLY, QUARTERLY, ANNUALLY)

Coverage (보장)
  coverageId      : UUID PK
  policyId        : UUID FK → Policy
  coverageType    : VARCHAR
  coverageAmount  : NUMERIC
  beneficiaryName : VARCHAR (nullable)

PolicyServicingHistory (유지·변경 이력 — append-only)
  servicingId     : UUID PK
  policyId        : UUID FK → Policy
  changeType      : ENUM(BENEFICIARY_CHANGE, ADDRESS_CHANGE, PREMIUM_FAILURE, REINSTATEMENT, ...)
  changedAt       : TIMESTAMPTZ
  payload         : JSONB    -- 변경 내용 스냅샷

CommissionSchedule (수수료 스케줄 — D4)
  commissionId    : UUID PK
  policyId        : UUID FK → Policy
  fcId            : VARCHAR
  recipientType   : ENUM(FC)          -- V1: 항상 FC, 계층 수수료 계산 미구현(D5)
  installmentNo   : INT               -- 회차 1~12
  installmentAmount : NUMERIC
  paidAt          : TIMESTAMPTZ (nullable)
  parentCommissionId : UUID (nullable) -- 계층 수수료 여지(D5), 계산 로직 없음
  UNIQUE(policy_id, recipient_type, fc_id, installment_no)  -- D4

InsuredPerson (피보험자 — PII 분리 테이블)
  insuredPersonId : UUID PK
  applicationId   : UUID FK → InsuranceApplication
  name            : VARCHAR
  rrn             : VARCHAR_ENCRYPTED  -- 주민등록번호, AES-256 컬럼 암호화

Contractor (계약자 — PII 분리 테이블)
  contractorId    : UUID PK
  applicationId   : UUID FK → InsuranceApplication
  name            : VARCHAR
  phone           : VARCHAR_ENCRYPTED  -- 연락처, AES-256 컬럼 암호화
```

## 2. Policy 생애주기 상태머신 (D7)

```
        ┌────────────────────────────────────────────────────────────┐
        │  보험료 납입 2회 연속 실패                                    │
        │  (consecutivePremiumFailures == 2)                          │
        ▼                                                            │
 ┌────────────┐         ┌──────────────┐                            │
 │   ACTIVE   │────────▶│    LAPSED    │◀───────────────────────────┘
 └────────────┘         └──────────────┘
      │  │  │                │  │  │
      │  │  │   부활         │  │  │
      │  │  │  (24개월 이내  │  │  │
      │  │  │   + 연체 전납) │  │  │
      │  │  │◀───────────────┘  │  │
      │  │  │                   │  │
      │  │  │  ACTIVE→EXPIRED   │  │ LAPSED→EXPIRED
      │  │  │  만기일 도래       │  │ 실효일로부터
      │  │  ▼                   │  │ 24개월 경과
      │  │  ┌────────────────┐  │  ▼
      │  │  │    EXPIRED     │  │  ┌────────────────┐
      │  │  │   (terminal)   │◀─┼──│    EXPIRED     │
      │  │  └────────────────┘  │  └────────────────┘
      │  │                      │
      │  │ ACTIVE→SURRENDERED   │ LAPSED→CANCELLED
      │  │ 계약자 임의 해지       │ 청약철회 (15일 이내)
      │  ▼                      ▼
      │  ┌────────────────┐  ┌────────────────┐
      │  │  SURRENDERED   │  │   CANCELLED    │
      │  │   (terminal)   │  │   (terminal)   │
      │  └────────────────┘  └────────────────┘
      │
      │ ACTIVE→CANCELLED
      │ 청약철회 (15일 이내)
      ▼
   ┌────────────────┐
   │   CANCELLED    │
   │   (terminal)   │
   └────────────────┘
```

### 허용 전이 7개 (D7)

| #   | From   | To          | 조건                                            |
| --- | ------ | ----------- | ----------------------------------------------- |
| 1   | ACTIVE | LAPSED      | consecutivePremiumFailures == 2 (1회로는 불가)  |
| 2   | LAPSED | ACTIVE      | 실효일로부터 24개월 이내 + 연체보험료 전액 납입 |
| 3   | LAPSED | EXPIRED     | 실효일로부터 24개월 경과                        |
| 4   | ACTIVE | SURRENDERED | 계약자 임의 해지                                |
| 5   | ACTIVE | EXPIRED     | maturityDate 도래                               |
| 6   | ACTIVE | CANCELLED   | 청약철회: effectiveDate로부터 15일 이내         |
| 7   | LAPSED | CANCELLED   | 청약철회: effectiveDate로부터 15일 이내         |

- terminal 상태: SURRENDERED, EXPIRED, CANCELLED → 나가는 전이 없음
- 위 7개 외의 모든 전이 → `InvalidPolicyTransitionException` (조용히 무시 금지)

## 3. 수수료 정산 규칙 (D4·D5·D6)

### 선지급 스케줄 (D4)

- 초년도 수수료 총액을 **정확히 12행**(installment_no = 1~12)으로 분할
- 각 회차액 = `floor(초년도총액 / 12)` (리포 `RoundingPolicy.FLOOR`)
- 나머지(remainder) = `초년도총액 - 11 × 기본회차액` → 마지막(12번째) 회차에 가산
- **12회분 합계 = 초년도총액** (1원 오차 없음)
- UNIQUE 제약: `(policy_id, recipient_type, fc_id, installment_no)` 단일 복합 유니크

### 계층 수수료 (D5)

- `recipient_type`, `parent_commission_id` 컬럼을 V1 마이그레이션에 nullable로 포함
- **계층 수수료 계산 로직은 미구현** — V1에서는 `recipient_type = FC`만 사용
- 스키마 여지를 두는 것과 계산을 안 하는 것은 모순이 아님

### 환수(Clawback) 규칙 (D6)

```
CLAWBACK_WINDOW_MONTHS = 24  ← 도메인 상수, 단일 선언 (CommissionConstants.java)

m = ChronoUnit.MONTHS.between(effectiveDate, terminationDate)  // 내림(완료된 개월)

환수액 계산:
  - CANCELLED (청약철회): m 무관하게 기지급 합계 전액 환수
  - SURRENDERED / LAPSED→EXPIRED:
      m >= 24  →  환수액 = 0
      m <  24  →  환수액 = 기지급합계 × (24 - m) / 24  (통화최소단위 절사, RoundingPolicy.FLOOR)
```

환수 트리거 상태: `SURRENDERED`, `CANCELLED`, `LAPSED → EXPIRED`

## 4. 이벤트 계약

토픽 접두사: `lemuel.insurance.<snake_case>`

| 이벤트 타입             | 토픽                                       | 발행 시점             |
| ----------------------- | ------------------------------------------ | --------------------- |
| InsurancePolicyIssued   | lemuel.insurance.policy_issued             | Policy ACTIVE 전환 시 |
| PolicyStatusChanged     | lemuel.insurance.policy_status_changed     | 상태머신 전이 시      |
| CommissionScheduleFixed | lemuel.insurance.commission_schedule_fixed | 수수료 확정 시        |
| GeneralPayoutRequested  | lemuel.insurance.general_payout_requested  | terminal 전이가 일반지급을 낳을 때 (§14) |
| GeneralPayoutPaid       | lemuel.insurance.general_payout_paid       | 일반지급 실행 배치 지급 시 (§14)         |

- 이벤트 발행: Transactional Outbox (`outbox_events` → KafkaOutboxPublisher)
- aggregateType: `"Insurance"`, aggregateId: `policyId` (파티션 키)
- 금액 필드: `BigDecimal.toPlainString()` (DATA-STANDARD N5)
- event_id: UUID UNIQUE (L1) + processed_events PK (L2) + 도메인 자연키 (L3) 멱등

### 인바운드 포트 (D1)

```java
// ReceiveCarrierPolicyStatusPort — 인터페이스만 선언, 외부 HTTP 호출 코드 없음
// 구현체: NoOpCarrierPolicyStatusService (명시적 no-op)
```

## 5. 개인정보 처리

- 피보험자 RRN, 계약자 연락처: AES-256 컬럼 암호화 (`InsurancePiiEncryptionConverter`)
- 키: `INSURANCE_ENC_KEY` 환경변수 (Base64 32byte), yaml 기본값 없음
- 키 미설정 시: 기동 실패 + 예외 메시지에 `INSURANCE_ENC_KEY` 포함 (CrashLoop 방지)
- 별도 테이블 분리: `insured_persons`, `contractors`

## 6. 포트·DB·Kafka 배선

| 항목                  | 값                                                   |
| --------------------- | ---------------------------------------------------- |
| App 포트              | 8108                                                 |
| Management 포트       | 8109                                                 |
| DB                    | lemuel_insurance (PostgreSQL, Flyway, opslab schema) |
| Kafka 토픽 접두사     | lemuel.insurance.                                    |
| 부트 클래스           | github.lms.lemuel.InsuranceServiceApplication        |
| 패키지 루트           | github.lms.lemuel.insurance                          |
| Outbox default_schema | opslab                                               |

## 7. 헥사고날 레이어 구조

```
insurance/
├── domain/               # 순수 POJO, Spring 무의존 (D1 상태머신, D6 환수 계산 포함)
│   └── exception/        # InvalidPolicyTransitionException 등
├── application/
│   ├── port/in/          # UseCase 인터페이스, ReceiveCarrierPolicyStatusPort
│   ├── port/out/         # PublishInsuranceEventPort, SaveOutboxEventPort
│   └── service/          # UseCase 구현체, NoOpCarrierPolicyStatusService
└── adapter/
    ├── in/web/           # REST 컨트롤러 + DTO
    ├── in/kafka/         # IdempotentEventConsumer 구독자 (CarrierPolicyStatusConsumer)
    ├── in/schedule/      # 월 마감 배치, 만기·실효 판정 스케줄러
    ├── out/persistence/  # JPA 엔티티·리포지터리 + InsurancePiiEncryptionConverter
    ├── out/event/        # InsurancePolicyEventPublisherAdapter (Outbox 저장)
    └── out/external/     # (no-op 구현만; 외부 HTTP 호출 코드 없음)
```

ArchUnit 4룰 강제:

1. `..insurance.domain..` → `..application..`/`..adapter..`/`..config..` 의존 금지
2. `..insurance.application..` → `..adapter..` 의존 금지
3. `github.lms.lemuel.insurance..` → 타 서비스 컨텍스트(order/settlement/loan/investment/account/organization/card) 의존 금지
4. `..insurance.domain..` → `jakarta.persistence..`/`org.springframework..` 의존 금지

## 8. 배치 시스템 (adapter/in/schedule — ShedLock 분산 락, KST Clock 주입)

실행 순서가 곧 정합성이다 — 앞 배치의 산출이 뒤 배치의 전제다.

| 배치                            | 스케줄 (KST)  | 하는 일                                                                                                        |
| ------------------------------- | ------------- | -------------------------------------------------------------------------------------------------------------- |
| PolicyExpiryScheduler           | 매일 02:00    | D7 전이 5(ACTIVE→EXPIRED 만기)·전이 3(LAPSED→EXPIRED 부활창구 도과) 자동 집행 + 상태변경 이벤트                |
| CommissionClawbackSweepScheduler| 매일 03:30    | terminal 계약의 SCHEDULED 회차 소멸(CANCELLED) + PAID 회차 환수 대기 전환(D6 계산) + clawback 이벤트           |
| CommissionPayoutScheduler       | 매일 04:00    | due_date 도래 SCHEDULED 회차 지급(PAID) — ACTIVE 계약만, LAPSED 보류 + commission_paid 이벤트                  |
| GeneralPayoutScheduler          | 매일 04:30    | REQUESTED 일반지급(해약환급금·만기보험금·철회환급금) 전건 지급(PAID) + general_payout_paid 이벤트 (§14)        |
| MonthlyCommissionClosingScheduler| 매월 1일 05:00| 전월 지급 실적(paid_at 기준)을 FC별 append-only 마감 스냅샷(commission_closings, V5)으로 확정 + 이벤트          |
| BancaConcentrationScheduler     | 매월 2일 06:00| 당해연도 방카 25%룰 점검(은행×부문×원수사 비중, 자산 2조 미만 면제) — 위반 시 banca_rule_violated 이벤트, 위반 0건도 감사 기록 |
| ProposalExpiryScheduler         | 매일 01:30    | 유효기간(30일) 경과 QUOTED 가입설계를 EXPIRED 로 전이(§13) — 금전 사건 아님, 건 단위 감사 없음                   |

- 전이는 반드시 도메인 메서드를 통과한다 — 배치가 status 를 직접 UPDATE 하지 않는다.
- 모든 이벤트는 같은 tx 의 Outbox 로 기록(커밋-발행 원자성). 배치 실행은 잡 단위로 audit_logs 에 남는다.
- CommissionSchedule 상태머신(전이 4개만): SCHEDULED→PAID, SCHEDULED→CANCELLED,
  PAID→CLAWBACK_PENDING, CLAWBACK_PENDING→CLAWED_BACK. 그 외 `InvalidCommissionTransitionException`.

## 9. 판매채널 — 방카슈랑스 (V6)

- `sales_channel`: `FC`(설계사 대면) | `BANCA`(은행 창구). BANCA ↔ `partner_bank_code` 존재를
  도메인(`InvalidSalesChannelException`)과 DB CHECK(`chk_policy_banca_bank`)가 이중 강제.
- 채널이 수수료 수령 주체를 결정: FC→`recipient_type='FC'`(설계사), BANCA→`'BANK'`(판매 은행).
  `Policy.commissionRecipientId()` / `CommissionScheduleFactory.createFirstYearSchedule(..., channel)`.
- `insurance_products.insurer_code`(원수사)·`insurer_sector`(V8, LIFE/NON_LIFE) — 25%룰 집계 기준.

## 10. 대면 상품설명서 + 25%룰 (V7)

- **상품설명서 PDF**: iText 온디맨드 렌더링 (`adapter/out/pdf`). GET `/api/insurance/products/{code}/disclosure`(미리보기),
  POST `/api/insurance/disclosures`(교부 = 문서 발급 + 증빙 기록의 단일 행위 — 응답 PDF 의 SHA-256 이
  저장된 증빙과 동일, `X-Document-Sha256` 헤더).
- **교부 이력**(`disclosure_deliveries`): append-only(트리거 강제) 완전판매 증빙 — 교부자·채널·계약자·
  문서 SHA-256·교부 시점 상품 조건 JSONB 스냅샷. 해시는 서버가 계산(클라이언트 불신).
- **25%룰**: 은행별·**부문(생보/손보)별**(V8) 특정 원수사 신계약 보험료 비중 > 25% 면 위반(정확히 25% 허용) —
  분모는 (은행, 부문) 풀 총액. 판정은 `BancaRuleEvaluator`(순수 도메인, 상한 `CONCENTRATION_LIMIT` 단일 선언),
  배치는 탐지·통보만 (차단 아님 — 조치는 이벤트 소비자 몫).
- **자산 2조 적용 요건**(V8): `banca_partner_banks` 자산 레지스트리 기준, 자산총액 2조원 이상 은행만
  적용 대상(경계 포함, `ASSET_APPLICABILITY_THRESHOLD` 단일 선언). 2조 미만 등록 은행은 면제,
  **미등록 은행은 적용 대상**(fail-closed — 위반 누락이 면제 오탐보다 나쁘다). 면제 은행 수는
  배치 감사 JSON(`exemptBanks`)에 남는다.

## 11. 마이그레이션

V1 core → V2 outbox/processed/shedlock → V3 audit → V4 PII 하드닝 → V5 commission_closings →
V6 sales_channel/banca → V7 disclosure_deliveries → V8 insurer_sector + banca_partner_banks →
V9 premium_rate_tables + proposal_quotes (가입설계) → V10 general_payouts (일반지급).

## 12. 언더라이팅 (청약 접수 → 심사 → 승인/반려)

상태머신: `SUBMITTED → UNDER_REVIEW → APPROVED | REJECTED` (3개 전이만,
그 외 `InvalidApplicationTransitionException`). REST:

| API                                             | 하는 일                                                       |
| ----------------------------------------------- | ------------------------------------------------------------- |
| POST `/api/insurance/applications`              | 접수 — PII(RRN·연락처)는 분리 테이블에 AES 암호화 저장        |
| POST `/api/insurance/applications/{id}/review`  | 심사 착수 (reviewed_at 스탬프)                                |
| POST `/api/insurance/applications/{id}/approve` | 승인 — 아래 승인 트랜잭션                                     |
| POST `/api/insurance/applications/{id}/reject`  | 반려 — 사유 필수 (decided_at 스탬프)                          |

**승인 트랜잭션(돈 경로)** — 전부 한 tx, 실패 시 전체 롤백:

1. **완전판매 게이트**: 해당 청약의 상품설명서 교부 증빙(disclosure_deliveries)이 없으면
   `DisclosureNotDeliveredException`(409) — 청약은 UNDER_REVIEW 로 남는다. 차단이 아니라 순서 강제.
2. 청약 APPROVED 전이 → 계약 발행 `Policy.issue`(ACTIVE, 효력일=승인일 KST,
   증권번호 `POL-yyyyMMdd-xxxxxxxx`, 만기 미지정=종신형 취급).
3. 초년도 수수료 총액 = 연 보험료 × 상품 초년도 수수료율(통화 최소단위 절사) →
   D4 12회 스케줄 확정, 회차 n due = 효력일 + (n-1)개월(1회차 즉시 due).
   수령 주체는 채널이 결정(FC→설계사 / BANCA→판매 은행).
4. `policy_issued` + `commission_confirmed` Outbox 발행 + `INSURANCE_POLICY_ISSUED` 감사.

이 경로가 배치 5종이 소비하는 `insurance_policies`·`commission_schedules` 행의 생성 지점이다 —
발행(§12) → 지급/환수/마감(§8) → 소멸(§8)로 계약 전 생애가 시스템 안에서 닫힌다.

## 13. 가입설계 (V9 — 요율 산출 + 설계서 + 청약 전환)

청약 앞단: 상담 → **가입설계(ProposalQuote)** → 청약 → 계약. 설계 결정 4개:

- **D-P1 요율 버저닝**: `premium_rate_tables` — (상품, 성별, 보험나이 구간, 납입기간) ×
  `effective_from`. 개정은 새 행, 기준일 이전 개시분 중 최신이 이긴다. 요율 부재 시 폴백 없이
  산출 거부(`RateNotFoundException`, 422).
- **D-P2 INSERT-only 스냅샷**: `proposal_quotes` 가 산출에 쓴 요율 행(`rate_table_id`)·적용 요율·
  보험나이·보험료를 고정 보존. 재산출 = 새 설계. 전이 반영은 status·converted_application_id 만.
- **D-P3 서버 주입**: 청약 전환 시 보장금액·보험료는 설계 스냅샷 값을 서버가 주입 — 전환 요청에
  금액 파라미터가 없다(위변조 차단). 1설계 1청약은 partial UNIQUE(`converted_application_id`)가 DB 로 보장.
- **PII 최소화**: 생년월일은 보험나이 산정에만 쓰고 저장하지 않는다(`insurance_age` 만 스냅샷).

**도메인**: `PremiumRater`(보험나이 = 만 나이 + 6개월 이상 경과 시 +1; 연 보험료 = 보장 ÷ 1,000 ×
요율, 원 단위 HALF_UP, 0원 설계 거부) · `ProposalQuote` 상태머신 `QUOTED → CONVERTED | EXPIRED`
(2개 전이만, 그 외 `InvalidProposalTransitionException`; 만료 전환은 `ProposalExpiredException` 409).
유효기간 30일(`VALIDITY_DAYS`).

**소유권(IDOR 차단)**: FC 식별자는 요청 본문이 아니라 **JWT 주체에서만 파생**한다 —
`FcIdentity.currentFcId()`(단일 초크포인트, 현재 규칙은 `userId` 를 FC 식별자로 사용 —
settlement `sellerId = userId` 와 동형). 요청 DTO 에 `fcId` 필드 자체가 없어 본문에 실어도 바인딩되지
않고, 산출·조회·설계서·전환 **4개 경로 전부** 작성자 본인만 접근한다(`ProposalOwnershipException` 403).
`userId` 없는 구(舊) 토큰도 403 — 존재 여부가 새 나가지 않도록 소유권 실패와 같은 응답이다.
⚠️ 남은 과제: 청약·계약 경로는 아직 본문 fcId 를 신뢰한다(서비스 전역 미완) — 같은 사람이라도
경로에 따라 FC 식별자가 다를 수 있다. FC 레지스트리(organization 프로젝션 소비)가 생기면
`FcIdentity` 한 곳만 바꾸면 된다.

| API                                              | 하는 일                                                        |
| ------------------------------------------------ | -------------------------------------------------------------- |
| POST `/api/insurance/proposals`                  | 산출 — 요율 조회 + 보험료 계산 + 스냅샷 저장 (201)             |
| GET `/api/insurance/proposals/{id}`              | 단건 조회 (산출 근거 포함) — 본인 설계만 403 대조              |
| POST `/api/insurance/proposals/{id}/convert`     | 청약 전환 — 금액 없음(서버 주입), 만료 409, 타인 설계 403      |
| GET `/api/insurance/proposals/{id}/sheet`        | 가입설계서 PDF (iText, adapter/out/pdf) — 본인 설계만          |

요청 DTO 어디에도 `fcId` 가 없다는 점이 이 표의 핵심이다 — 위변조 방어를 검증 로직이 아니라
**API 형태**로 닫았다(D-P3 금액 서버 주입과 같은 방식).

## 14. 일반지급 (V10 — 해약환급금·만기보험금·철회환급금)

계약자 앞 지급 업무 중 **일반지급**(산출식이 금액을 확정하는 지급 — 사고보험금 심사지급 아님).
설계 결정 5개:

- **D-G1 전이 → 지급 매핑**: 일반지급은 청구 접수가 아니라 **Policy 상태 전이가 낳는다** —
  심사 단계가 없다(지급 사유·금액이 전이 시점에 확정). ACTIVE→SURRENDERED = 해약환급금(해지일 기준),
  ACTIVE→EXPIRED = 만기보험금(만기 전일까지 납입 가정), LAPSED→EXPIRED = 해약환급금(실효일 기준 —
  실효 이후 납입 없음), →CANCELLED = 철회환급금(기납입 전액 — D6 수수료 전액 환수와 대칭).
- **D-G2 기납입보험료는 정상납입 가정 산출**: 납입 원장이 없는 V1 한계를 명시적 근사로 —
  회차보험료 = 연보험료 × 납입주기/12(통화 최소단위 절사), 납입회차수 = floor(경과월/주기) + 1
  (전이일 도래분 포함). 만기만 만기 **전일**까지 산입(만기일 당일 회차 없음).
- **D-G3 해약환급률표 단일 선언**(`GeneralPayoutConstants`): 경과 12개월 미만 0%(**payout 미생성** —
  0원 지급 행 없음) / 12~36개월 40% / 36~60개월 60% / 60~84개월 75% / 84개월 이상 85%.
  구간 경계는 D6 리터럴 가드(`ClawbackConstantDisciplineTest`)와 충돌하지 않도록 환수 창구 값을 쓰지 않는다.
  만기보험금은 기납입 100%(V1 전 상품 만기환급형 취급 — 상품별 환급형 플래그는 향후 과제).
- **D-G4 상태머신 1전이**: `REQUESTED → PAID` 뿐(그 외 `InvalidGeneralPayoutTransitionException`).
  거절·취소 상태가 없는 이유 — payout 은 이미 확정된 terminal 전이에서만 태어나므로 지급 의무가
  소급 취소될 경로가 없다. 멱등: `payout_id UUID UNIQUE` + **`UNIQUE(policy_id, payout_type)`**
  (terminal 상태 상호배타 → 계약당 유형별 1건이 자연키).
- **D-G5 산출근거 스냅샷**: `general_payouts` 행이 기납입합계·적용요율·경과월수·납입회차수를 고정
  보존한다(D-P2 와 같은 원칙 — 지급액이 어떻게 나왔는지 행 하나로 재구성 가능).

**배치**: `GeneralPayoutScheduler` 매일 04:30 KST(수수료 지급 04:00 이후) — REQUESTED 전건 지급(PAID)
+ `general_payout_paid` 이벤트 + 잡 단위 감사(`INSURANCE_GENERAL_PAYOUT_PAID`).

**이벤트**: `lemuel.insurance.general_payout_requested`(산출근거 포함) / `general_payout_paid` —
파티션 키 policyNumber, 금액 plain string.

| API                                                  | 하는 일                                                            |
| ---------------------------------------------------- | ------------------------------------------------------------------ |
| POST `/api/insurance/policies/{policyNumber}/surrender` | 임의해지 — D7 전이 4 + 해약환급금 산출·payout 생성 (409/403/404) |
| POST `/api/insurance/policies/{policyNumber}/cancel`    | 청약철회 — D7 전이 6·7(15일 창구) + 기납입 전액 payout (409)     |
| GET `/api/insurance/policies/{policyNumber}/payouts`    | 일반지급 내역 조회 (산출근거 포함)                               |

⚠️ §13 과 동일 한계: fcId 는 JWT 주체가 아니라 요청 입력 — 해지/철회의 fcId 대조는 실수 방지 수준.
