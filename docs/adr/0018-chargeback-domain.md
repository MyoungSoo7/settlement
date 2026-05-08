# ADR 0018 — Chargeback (카드사 분쟁) 도메인

- 상태: Accepted
- 일자: 2026-05-08

## 컨텍스트

기존 정산 사이클은 환불(Refund) 만 처리하고 있었다 — 즉 *고객이 셀러에게 요청해 동의 하에 처리되는* 환불.
하지만 이커머스에서 발생하는 결제 취소 경로는 두 갈래다.

| 경로 | 트리거 | 의사결정자 | 회계 처리 |
|---|---|---|---|
| **Refund** | 고객이 셀러·운영사에 환불 요청 | 셀러 합의 또는 자동 정책 | `refunds` + 정산 후라면 `settlement_adjustments(refund_id)` |
| **Chargeback** | 고객이 카드사에 신고 (도용·미수령 등) | 카드사가 PG 에 강제 차감 통지 | ❌ 도메인이 없었음 |

면접 질문 *"고객이 카드사에 분쟁 걸면 어떻게 처리되나요?"* 에 답할 수 없었고, 회계상 두 경로가
구분되지 않으면 정산 정합성 보고가 부정확해진다 (분쟁 추세, FRAUD 비율, 셀러별 분쟁률 등).

## 결정

### 도메인 모델

```
Chargeback aggregate
  ├── payment_id (FK, NOT NULL)
  ├── settlement_id (FK, nullable)            정산 생성 전 분쟁 가능
  ├── amount (positive)
  ├── reason_code  : FRAUD / DUPLICATE / NOT_RECEIVED / PRODUCT_NOT_AS_DESCRIBED / OTHER
  ├── reason_detail
  ├── source       : PG_WEBHOOK | MANUAL
  ├── pg_chargeback_id (멱등 키, PG_WEBHOOK 일 때 필수)
  ├── status       : OPEN → ACCEPTED | REJECTED  (단방향, 종료 후 재결정 불가)
  ├── decided_by, decision_note (감사 추적)
  └── raised_at / decided_at / created_at / updated_at
```

### Refund 와 별도 도메인으로 둔 이유

1. **회계 보고 분리**: 운영 KPI 가 refund_rate 와 chargeback_rate 를 분리 추적해야 한다.
   FRAUD 비율은 PG 협상·셀러 정지 결정의 근거.
2. **결정자가 다름**: Refund 는 셀러/고객 양자 동의 → Chargeback 은 카드사 일방. 비즈니스 규칙·SLA 가 다르다.
3. **사유 코드 체계가 다름**: Visa/Master 의 reason code 매핑이 환불 사유와 호환되지 않음.
4. **금전 흐름이 다름**: Refund 는 고객 → 즉시 환급. Chargeback 은 PG 가 정산금 차감
   (이미 송금된 후라면 셀러로부터 환수 — Phase 3, ADR 0019 예정).

### settlement_adjustments 통합 — XOR 제약

```sql
-- V44 핵심 제약
CHECK (
    (refund_id IS NULL AND chargeback_id IS NULL)
    OR (refund_id IS NOT NULL AND chargeback_id IS NULL)
    OR (refund_id IS NULL AND chargeback_id IS NOT NULL)
)
```

기존 `settlement_adjustments` 테이블에 `chargeback_id` 컬럼 추가, refund/chargeback 둘 중
하나만 채우는 XOR 강제. **별도 chargeback_adjustments 테이블을 만들지 않은 이유**:

- 하나의 음수 row = 정산금 차감 1 회 라는 회계 단위 동일.
- 대사(reconciliation) 쿼리가 단일 테이블로 끝남.
- 미래에 또 다른 차감 사유 (예: 운영자 페널티) 가 생겨도 같은 패턴으로 컬럼 추가 가능.

### PG webhook 멱등

`pg_chargeback_id` 위에 partial UNIQUE 인덱스 (NOT NULL only). 같은 분쟁이 두 번 통지되어도
서비스 레이어 lookup → 기존 record 반환 → 신규 OPEN 차단. `chargeback.idempotent.hit` 메트릭으로 가시화.

### 운영자 결정 강제

- `accept(decidedBy, note)` / `reject(decidedBy, note)` — `decidedBy` 누락 시 도메인이 거부.
- `reject` 는 사유(`note`) 필수 — 카드사 응답 시 셀러 증빙 근거를 운영 검토에서 추적 가능해야 함.
- 모든 결정은 `audit_logs` 가 아닌 `chargebacks.decided_by/decision_note` 로 inline 기록 — 분쟁
  자체의 unbreakable 속성으로 처리.

### ACCEPT 의 부수 효과 — settlement_adjustments 자동 생성

`ChargebackService.accept(...)` 가 호출되면:

1. 도메인 상태 ACCEPTED 로 전이
2. `settlementId` 가 있으면 `SettlementAdjustment.ofChargeback(...)` 생성, 음수 amount 로 차감
3. `settlementId` 가 없으면 (정산 전 분쟁) → adjustment 생성 건너뜀.
   추후 정산 생성 시점에 chargeback 조회 → 자동 백필 (Phase 3 정산 생성 path 수정 예정)

### 책임 경계 — 도메인 vs application

| 책임 | 누가 |
|---|---|
| 상태 전이 / 검증 | Chargeback aggregate |
| 멱등 키 검사 | application service (`ChargebackService.open`) |
| Adjustment 생성 부수 효과 | application service (도메인 외부) |
| ReversePayout (Payout COMPLETED 후 환수) | **Phase 3 별도 도메인 — ADR 0019** |

## 결과

### 좋아진 점

- **회계 명료화**: 환불·분쟁이 별도 컬럼으로 분리되어 KPI 보고 정확
- **PG 통지 멱등**: webhook 재전송에 안전
- **운영자 추적성**: 모든 결정에 decidedBy + 사유 강제
- **데모 가능 표면**: 운영자 콘솔 4 엔드포인트 (open/list/accept/reject)

### 트레이드오프

- 정산 전 분쟁 처리가 두 단계 — ACCEPT 시점과 정산 생성 시점에 모두 chargeback 조회. Phase 3 에서 백필 path 추가.
- PG webhook 어댑터는 아직 없음 — Phase 3 에서 HMAC 서명 검증과 함께 도입.
- 이미 Payout COMPLETED 된 정산의 ACCEPT 처리 (셀러 환수) 는 본 ADR 범위 외 — ADR 0019 예정.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| Refund 도메인을 확장해 reason_type 만 추가 | ✗ | 결정자·SLA·사유 코드 체계가 모두 달라 도메인 응집도 깨짐 |
| 별도 chargeback_adjustments 테이블 | ✗ | 회계 단위 동일 (음수 row = 차감) → 대사 쿼리 복잡도 증가 |
| Chargeback 도메인을 별도 microservice 로 | ✗ | settlement-service 와 강결합 (settlementId 백필, adjustment 생성). 같은 BC 안에 두는 게 자연 |
| ACCEPT 시 즉시 ReversePayout 까지 일괄 처리 | ✗ | 환수는 비동기 + 운영 승인 필요. 별도 도메인으로 분리 (ADR 0019) |

## 변경된/추가된 파일 (Phase 1 + 2)

```
order-service/.../db/migration/V44__chargebacks.sql                    ★ 마이그레이션
settlement-service/.../chargeback/domain/{Chargeback, ChargebackStatus,
                                          ChargebackReason, ChargebackSource}.java   ★ 도메인
settlement-service/.../chargeback/application/port/{in,out}/...        ★ 포트 5 종
settlement-service/.../chargeback/application/service/ChargebackService.java   ★
settlement-service/.../chargeback/adapter/out/persistence/...          ★ 영속성
settlement-service/.../chargeback/adapter/in/web/ChargebackAdminController.java
settlement-service/.../settlement/domain/SettlementAdjustment.java      +ofChargeback 팩토리
settlement-service/.../settlement/adapter/out/persistence/SettlementAdjustment*.java   chargebackId 매핑
shared-common/.../config/jwt/SecurityConfig.java                        +/admin/chargebacks/** ROLE_ADMIN
```

테스트:
- 도메인 18 케이스 (`ChargebackTest`)
- 서비스 7 케이스 (`ChargebackServiceTest`) — PG 멱등 / ACCEPT-with/without-settlement / REJECT 등

## 후속 (Phase 3)

- ADR 0019 — ReversePayout (Payout COMPLETED 후 셀러 환수)
- `/webhooks/pg/chargebacks` — HMAC 서명 검증 + IP allowlist
- 정산 생성 시점에 미연결 chargeback 자동 백필
- `ChargebackJpaEntity` TestContainers 통합 테스트
