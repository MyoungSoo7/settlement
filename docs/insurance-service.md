# insurance-service 설계 문서

> GA(법인보험대리점) 플랫폼 — 설계사(FC)가 매일 쓰는 업무 시스템.
> 상담 → 청약 → 계약 → 유지·변경 → 수수료 정산을 하나로 잇는다.

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
