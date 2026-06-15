# 선정산 대출 MSA (`loan-service`) 설계

- 작성일: 2026-06-15
- 상태: 설계 확정 (구현 계획 작성 대기)
- 대상 레포: `lemuel` (이커머스 + 정산 MSA)

## 1. 배경 & 목표

기존 `settlement-service`는 결제 → 정산 → 지급(payout) → 원장(ledger) 까지 자금 흐름을
정확히 계산·송금·기록한다. 그러나 그 위에 얹는 **금융 서비스(대출 등)는 없다.**

본 설계는 정산 인프라 위에 **선정산 대출**(정산예정금 담보 조기지급) 도메인을
별도 마이크로서비스 `loan-service`로 추가한다.

### 결정된 방향 (브레인스토밍 합의)
- **상품 모델**: 선정산(정산예정금 담보)을 1차로 구현하되, 매출담보 한도대출(factoring)로
  확장 가능한 구조로 설계.
- **목적**: 실제 운영 지향 (실제 자금 송금·여신 적정성·정합성 진지하게 고려).
- **상환 연동**: 이벤트 기반(Kafka) — 다음 정산 확정 시 자동 차감.
- **지급 처리**: 기존 settlement `payout` 메커니즘 재사용.
- **한도/이자**: 정산예정금 기반 단순 모델 (LTV + 일할이율).
- **회계**: loan-service 자체 복식부기 원장.
- **DB**: ★ **자체 DB 완전 분리** (DB-per-service) — 현재 레포는 단일 DB(`opslab`) 공유
  상태이나, loan-service는 별도 DB를 소유한다.

## 2. 현재 아키텍처 제약 (반드시 인지)

1. **단일 DB 공유**: `order-service`·`settlement-service` 모두
   `jdbc:postgresql://localhost:5432/opslab` 를 본다. settlement의 read-model projection은
   같은 DB라서 `payments`/`orders` 테이블을 `@Immutable` 로 매핑해 읽는 구조다.
   → loan은 **자체 DB**를 쓰므로 read-model projection을 **사용할 수 없다.**
   정산 데이터는 Kafka 이벤트로만 수신·복제한다.
2. **settlement는 라이브러리 모드**: `bootJar` 비활성, order-service fat jar에 번들.
   독립 배포(MSA 분리)는 Phase B 예정. → loan-service도 동일하게 library-mode로 시작하고
   Phase B에서 독립 배포로 분리 (일관성 유지).
3. **"SettlementConfirmed"는 현재 인프로세스 Spring ApplicationEvent** (ES 색인 전용)다.
   Kafka로 발행되지 않는다. → 상환 트리거를 위해 settlement-service에
   **Outbox 기반 Kafka 발행을 신규 추가**해야 한다 (`SettlementCreated`, `SettlementConfirmed`).

## 3. 서비스 경계 & 모듈 구조

5번째 Gradle 모듈로 추가한다.
- `settings.gradle.kts` 에 `"loan-service"` 추가.
- **order-service / settlement-service 코드 의존 0** — 모든 외부 데이터는 Kafka 이벤트로만.
- 초기 library-mode → Phase B 독립 배포.

```
loan-service/src/main/java/github/lms/lemuel/loan/
├── domain/                  # LoanAdvance, RepaymentSchedule, LoanLedgerEntry, SellerSettlementView (POJO)
├── application/
│   ├── port/in/             # RequestLoanUseCase, ApplyRepaymentUseCase, IngestSettlementUseCase
│   ├── port/out/            # LoadLoanPort, SaveLoanPort, LoadSettlementViewPort, RequestPayoutPort, PublishLoanEventPort
│   └── service/             # 한도산정·실행·상환·연체 UseCase 구현
└── adapter/
    ├── in/web/              # 셀러 대출 신청/조회 REST API
    ├── in/kafka/            # SettlementCreatedConsumer, SettlementConfirmedConsumer
    ├── out/persistence/     # loan_advances, repayment_schedules, loan_ledger, seller_settlement_view (자체 DB)
    ├── out/event/           # Outbox → Kafka (LoanDisbursementRequested, LoanRepaymentApplied)
    └── out/payout/          # 지급 위임 (settlement payout 으로 이벤트 발행)
```

## 4. 도메인 모델 & 상태

### LoanAdvance (선지급 건)
```
REQUESTED → APPROVED → DISBURSED → (부분상환) → REPAID
                     ↘ REJECTED               ↘ OVERDUE → WRITTEN_OFF
```

### 한도 & 수수료 (정산예정금 기반 단순 모델)
- **한도** = Σ(셀러 미지급 정산예정금) × LTV (예: 80%)
- **수수료** = 선지급액 × 일할이율 × 선지급일수(정산예정일까지의 days)
- 1차는 단순 모델. `CreditPolicy` 포트로 추상화하여 추후 신용등급 스코어링/이자제한법 검증을
  확장 지점으로 열어둔다 (구현은 1차 범위 밖).

## 5. ★ 로컬 정산 뷰 (DB 분리의 핵심 비용 1)

loan은 `settlements` 테이블을 읽을 수 없으므로, 한도 산정에 필요한
"셀러별 미지급 정산예정금"을 **자체 DB에 로컬 뷰로 materialize** 한다.

```
[settlement-service] 정산 생성(batch create)
    └─ Outbox(SettlementCreated{sellerId, settlementId, amount, dueDate})   ← 신규 발행 필요
            ↓ Kafka topic: lemuel.settlement.created
[loan-service] SettlementCreatedConsumer
    ├─ processed_events 멱등 체크
    └─ seller_settlement_view UPSERT (정산예정금 = 담보가치 원천)
```

- `seller_settlement_view`: (sellerId, settlementId PK, amount, dueDate, status[PENDING/CONFIRMED])
  - 1차는 `PAID` 상태를 두지 않는다. loan은 payout 완료를 알 수 없고(별도 이벤트 구독은 비범위),
    상환은 `CONFIRMED` 시점에 확정되므로 PENDING→CONFIRMED 두 상태로 충분하다.
- **트레이드오프**: 한도 계산이 최종일관성 로컬 뷰 기준이라 수 초 지연(staleness) 가능.
  → 1차 대응: "조회 시점 로컬 뷰 기준으로 한도 표시 + 대출 실행 직전(APPROVE→DISBURSE) 재검증".
  재검증 시 담보 부족이면 거절/감액.

## 6. 자금 흐름

### 6.1 선지급 (실행)
```
[loan] 대출 신청 → 한도검증(seller_settlement_view) → APPROVED
    └─ 실행 직전 재검증 통과 → DISBURSED(자체 DB tx) + Outbox(LoanDisbursementRequested{sellerId, amount})
            ↓ Kafka: lemuel.loan.disbursement.requested
[settlement] payout 메커니즘으로 셀러에게 실제 송금 (기존 payout 재사용)
```

### 6.2 상환 정합성 — 코레오그래피 saga (DB 분리의 핵심 비용 2)

DB가 분리되어 "차감 + 잔액지급"을 단일 트랜잭션으로 묶을 수 없다. → 2단계 saga:

```
[settlement] 정산 확정(Confirm)
    └─ Outbox(SettlementConfirmed{settlementId, sellerId, amount})   ← 신규 발행 필요
            ↓ Kafka: lemuel.settlement.confirmed
[loan] SettlementConfirmedConsumer
    ├─ 멱등 체크(settlementId)
    ├─ 해당 셀러 미상환 대출 잠금(비관적 락) → 차감액 산정 + 상환 기록(자체 DB tx)
    │   ├─ 다건 대출 시 FIFO(가장 오래된 대출부터) 상환
    │   ├─ deducted = min(셀러 총 미상환잔액, 정산금) — 초과분은 다음 정산으로 이월
    │   └─ seller_settlement_view.status = CONFIRMED
    └─ Outbox(LoanRepaymentApplied{settlementId, deducted})
            ↓ Kafka: lemuel.loan.repayment.applied
[settlement] LoanRepaymentApplied 소비
    └─ 순지급액 = amount − deducted 로 payout 실행 (net 지급)
```

#### 정합성 규칙
- 각 단계는 `settlementId` 기준 **멱등**.
- **차감 1회 보장**: loan 측 `(settlementId)` UNIQUE 제약 → 같은 정산건 중복 차감 불가.
- **loan 미응답 처리**: settlement는 `SettlementConfirmed` 발행 후 `LoanRepaymentApplied` 를
  기다린다. 타임아웃(예: N분) 시 보상 정책 — **차감 0 으로 간주하지 않고** 해당 정산건 payout을
  보류(HOLD)하고 재시도/알람. (돈이 먼저 새는 것보다 지연이 안전 — 보수적 기본값.)
  - **HOLD 실행 주체**: settlement 측 스케줄드 잡이 `payout_status = AWAITING_LOAN` 인 정산건을
    주기적으로 스캔, 타임아웃 초과 건은 HELD로 전환 후 알람. `LoanRepaymentApplied` 수신 시 해소.
- 대출이 없는 셀러: loan은 `deducted=0` 으로 `LoanRepaymentApplied` 응답 → 전액 지급.
  - **모든 정산이 loan 왕복을 거치는 비용**: 비차입 셀러 payout도 1회 Kafka 왕복만큼 지연된다.
    정산 자체가 이미 배치(T+n 영업일)·비동기이므로 수 초 지연은 허용 가능 — 동기 결제 경로가
    아니다. 비차입 셀러는 `deducted=0` 빠른 경로로 즉시 응답한다.

## 7. 회계 — loan-service 자체 복식부기 원장

settlement ledger와 **분리**된 자체 원장 (MSA 경계 유지).

| 시점 | 차변 | 대변 |
|------|------|------|
| 선지급(DISBURSED) | 대출채권 | 현금(funding) |
| 수수료 인식 | 미수수익 | 이자/수수료수익 |
| 상환(정산차감) | 현금 | 대출채권 |
| 연체/대손 | 대손상각비 | 대손충당금 |

## 8. 멱등성 & 동시성

- **3단 멱등 방어** (기존 패턴 재사용):
  1. `outbox_events.event_id UUID UNIQUE`
  2. `processed_events PK (consumer_group, event_id)`
  3. 도메인 UNIQUE (`loan_repayments(settlementId)` 등)
- **동시성**: 한 셀러의 동시 대출신청/상환차감 경합 → settlement 환불 패턴과 동일하게
  비관적 락 + Idempotency-Key.

## 9. settlement-service 변경 사항 (신규 발행)

본 설계는 settlement-service에 다음을 **추가**해야 한다 (loan-service만으로는 불완전):
1. `SettlementCreated` Outbox/Kafka 발행 (정산 생성 시) — 기존 인프로세스 ES 이벤트와 별개.
2. `SettlementConfirmed` Outbox/Kafka 발행 (정산 확정 시) — 현재 인프로세스 Spring 이벤트를
   Kafka 발행으로 확장 (ES 색인 경로는 유지).
3. `LoanRepaymentApplied` 소비 후 payout net 지급 로직 (순지급액 = amount − deducted).
4. loan 미응답 시 payout HOLD/재시도 보상 경로.

## 10. 테스트 전략

- 도메인 단위: 한도/수수료/상환 차감 계산 (순수 POJO).
- 서비스: UseCase 단위.
- 컨트롤러: 대출 신청/조회 API.
- 통합: Testcontainers(PostgreSQL + Kafka) — `settlement-integration-test` 스킬 패턴 재사용.
  - 시나리오 1: "정산예정 100만 → 선지급 80만(LTV 80%) → 정산확정 시 80만+수수료 차감 →
    잔액 셀러 지급" 금액 검증.
  - 시나리오 2: 대출 없는 셀러 → 전액 지급.
  - 시나리오 3: loan 미응답 → payout HOLD.
  - 시나리오 4: 중복 SettlementConfirmed → 차감 1회만 (멱등).
- **ArchUnit**: loan → order/settlement 코드 의존 0 강제.

## 11. 명시적 비범위 (YAGNI)

- 신용등급 스코어링 / 머신러닝 한도 (포트만 열어두고 미구현).
- 이자제한법 상한 검증·연체이자·조기상환수수료 (1차 범위 밖, 확장 지점).
- 가상계좌/에스크로, 자체 PG 어댑터 (payout 재사용으로 대체).
- **funding pool(대출 재원) 모델**: 1차는 재원을 무한 가정한다. 자체 원장(§7)의 "현금(funding)"
  대변은 단일 funding 계정으로 기록하되, 재원 한도/소진 관리는 비범위(확장 지점).
- 독립 배포(helm/CI 분리)는 Phase B.

## 12. 핵심 트레이드오프 요약

| 항목 | 선택 | 대가 |
|------|------|------|
| DB 완전 분리 | 진짜 DB-per-service MSA | 로컬 정산 뷰 동기화(§5) + saga(§6.2) 추가 복잡도 |
| 이벤트 기반 상환 | 느슨한 결합 | 최종일관성·미응답 보상 처리 필요 |
| payout 재사용 | 일관된 자금집행 채널 | settlement-service 변경 동반(§9) |
| 자체 원장 | 회계 경계 명확 | 원장 구현 중복 |
