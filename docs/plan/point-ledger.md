# 포인트 원장 설계 — 장부 없는 결제 수단 닫기

> 대상: `order-service` 내 `point/` 도메인 + `account-service` GL 매핑 확장
> 관련: ADR 0026(계정계 인식 기준), ADR 0032(유효기간 정책), `money-safety`·`ledger-invariants`·
> `idempotency-and-events` 스킬, `docs/inflearn/dentis-architec.md`(ofDentis 레거시 분석)
> 상태: **설계(Proposed)** — 결정 3건이 열려 있다(§11).

---

## 1. 문제 정의

`TenderType.POINT` 는 **이미 결제 수단으로 열려 있다.**

```java
// order-service/.../payment/domain/TenderType.java:18
/** 멤버십 포인트 — 외부 PG 호출 없이 내부 잔액 차감 */
POINT(false),
```

그런데 그 "내부 잔액"이 **어디에도 없다.** 차감도 복원도 로그만 찍고 지나간다.

```java
// CreateSplitPaymentService.java:110-114
} else {
    // 내부 잔액 차감 (실 운영: PointService.deduct, GiftCardService.consume)
    // 본 구현은 도메인 모델만 — 실제 잔액 검증/차감 서비스는 별도 도메인의 책임
    tender.authorize(null);
    tender.capture();
```

```java
// TenderRefundExecutor.java:82-86
} else {
    // 실 운영: PointService.restore / GiftCardService.refund
    log.info("내부 잔액 복원: tenderId={}, type={}, amount={}", ...);
}
```

지금 상태의 의미는 이렇다 — **누구든 포인트 텐더로 임의 금액을 결제할 수 있고, 결제는 성공하며,
정산·GL 은 그 금액을 현금 수취로 인식한다.** 잔액 검증이 없으므로 상한도 없다. 이건 미구현 기능이
아니라 **회계 구멍**이다. 이 저장소의 최상위 가드레일이 "반쪽 전표 금지"인데, 포인트는 지금
**차변만 있고 대변이 없는 결제 수단**이다.

부수적으로, 분할결제는 최소 2개 텐더를 요구하므로(`CreateSplitPaymentService.java:59`)
**포인트 100% 결제 경로 자체가 없다.** 이것도 이번에 함께 닫는다.

---

## 2. 경계 결정 — 왜 별도 서비스가 아닌가

첫 후보는 `point-service`(20번째 마이크로서비스)였다. **채택하지 않는다.**

포인트 차감은 **결제 트랜잭션 안에서 원자적으로 일어나야 하는 쓰기**다. 이걸 서비스 밖으로
빼면 order 는 결제 도중 동기 HTTP 를 호출해야 하는데, 실측한 현재 코드가 그 조합을 특히 위험하게
만든다.

| 사실 | 근거 | 함의 |
| --- | --- | --- |
| order 는 내부 서비스로 나가는 동기 호출이 **하나도 없다** | outbound HTTP 는 `TossPgAdapter`·`TossConfirmApiClient`·`SlackOrderNotificationChannel` 뿐 | point 호출은 order 에 **없던 결합 방향**을 새로 만든다 |
| 결제 서비스는 클래스 레벨 `@Transactional` 안에서 외부를 호출한다 | `TossPaymentService.java:31,62` | 여기에 홉을 하나 더 얹으면 트랜잭션 안 네트워크 호출이 **2개**가 된다 |
| 그 패턴은 이미 반면교사로 기록돼 있다 | `dentis-architec.md` §3-④ "실패 은폐 + 트랜잭션 안 외부 호출" | 우리가 안 하기로 한 걸 새로 도입하는 셈 |
| 분할결제에는 보상 트랜잭션이 없다 | `CreateSplitPaymentService.java:30` javadoc — "본 구현은 단순화" | 원격 차감 성공 + 로컬 롤백 = **포인트만 사라지는** 사고 |

반대로 **분리해서 얻는 것이 거의 없다.** 포인트 원장의 상대편(주문·결제·환불·적립 트리거)이
전부 `opslab` 안에 있다. `deposit-service` 를 쪼갠 이유였던 "셀러 재원의 독립 통제"에 해당하는
동기가 구매자 포인트에는 없다.

> 대비: `deposit-service` 는 hold/offset 이 **콘솔 경로**라 핫패스 결합이 애초에 없었다
> (CLAUDE.md — "card 승인·매입은 페이로드에 sellerId 가 없어 미구독"). 포인트는 체크아웃
> 핫패스가 불가피하므로 같은 결론이 나오지 않는다.

### 채택안

**`order-service` 안의 `point/` 도메인**(16번째 도메인 패키지). 단,

1. 원장은 **독립 애그리거트**로 설계한다 — 결제 코드가 잔액 필드를 직접 만지지 못하게 한다.
2. 상태 변화는 전부 **Outbox 이벤트 5종**으로 발행한다(§9) → `account-service` 가 GL 을 잡는다.
3. 포트/어댑터를 헥사고날로 가른다.

이 셋을 지키면 나중에 분리가 필요해질 때 **도메인·이벤트 계약은 그대로 두고 어댑터만 옮기면
된다.** 지금 분리해서 사고 위험을 사는 대신, 분리 가능성만 열어 둔다.

### 이 결정의 정직한 비용

- `opslab` 이 도메인 하나를 더 안는다(현재 15 → 16). order-service 비대화는 실재하는 비용이다.
- 원장 패턴(available/locked/total, append-only entries, L3 자연키)이 `deposit-service` 와
  **두 곳에 중복**된다. 공통화는 하지 않는다 — 불변식을 공유 라이브러리로 빼면 두 도메인의
  규칙이 서로를 구속해서, 한쪽 정책 변경이 다른 쪽 회귀가 된다.

---

## 3. 도메인 모델

`deposit-service` 의 거울상으로 시작하되, **한 축이 갈라진다.**

| deposit(셀러 예치금) | point(구매자 포인트) | 왜 다른가 |
| --- | --- | --- |
| 잔고가 **단일 풀** | 잔고가 **로트(lot)의 합** | 포인트는 건별로 **유효기간과 출처**가 다르다. "8% 보너스분"과 "현금 충전분"은 만료 순서도, 환불 회수 순서도, **GL 계정도** 다르다 |
| `credit`/`debit` | 로트 발급 / FIFO 소비 | 소멸(만료)이 존재하려면 "언제 적립된 얼마"가 남아 있어야 한다 |

로트가 없으면 소멸도, 보너스 회수도, "이 포인트는 왜 사라졌나"도 설명할 수 없다. 이게 포인트가
예치금의 단순 복제가 아닌 유일한 이유다.

### 애그리거트

```
PointAccount   (1 : userId)     available / locked / total  — 잔고의 단일 진실원
 └ PointLot    (N)              적립 1건 = 로트 1개. origin + expiresAt + remaining
 └ PointEntry  (N, append-only) 모든 잔고 변경의 원장 기록
     └ PointLotConsumption (N)  엔트리 1건이 어느 로트를 얼마나 먹었는지
 └ PointHold   (N)              Phase 2 — 입금대기 결제용 선점
```

`PointAccount` 는 `SellerDepositAccount` 와 동일한 방어 구조를 갖는다
(`SellerDepositAccount.java:223-239` 의 `enforceInvariant()` 를 그대로 대응시킨다).

```java
private void enforceInvariant() {
    // available >= 0, locked >= 0, total >= 0
    // total == available + locked
    // available == Σ(ACTIVE lot.remaining) - locked   ← 포인트에만 있는 4번째 불변식
}
```

**4번째 불변식이 핵심이다.** 잔고 요약과 로트 상세가 어긋나는 순간이 곧 데이터 손상이며,
`deposit` 에는 대응물이 없다.

---

## 4. 스키마

새 마이그레이션 1개: `V20260818100000__point_ledger.sql` (order-service).

> **시각 타입 주의.** order-service 기존 테이블은 `TIMESTAMP`(`display_sections` 등)를 쓰지만,
> **포인트 테이블은 `TIMESTAMPTZ` 로 간다.** 소멸 시각은 곧 고객 재산이 사라지는 순간이라
> 서버 타임존에 의존하면 안 된다. settlement 가 이미 같은 방향으로 래칫을 걸어 두었다.

```sql
-- ── point_accounts ───────────────────────────────────────────────────────────
CREATE TABLE point_accounts (
    id          BIGSERIAL      PRIMARY KEY,
    user_id     BIGINT         NOT NULL,
    available   NUMERIC(19,2)  NOT NULL DEFAULT 0,
    locked      NUMERIC(19,2)  NOT NULL DEFAULT 0,
    total       NUMERIC(19,2)  NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_point_accounts_user UNIQUE (user_id),
    CONSTRAINT chk_point_accounts_status CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    CONSTRAINT chk_point_accounts_available_non_negative CHECK (available >= 0),
    CONSTRAINT chk_point_accounts_locked_non_negative    CHECK (locked    >= 0),
    CONSTRAINT chk_point_accounts_total_non_negative     CHECK (total     >= 0),
    CONSTRAINT chk_point_accounts_total_eq_sum           CHECK (total = available + locked),
    -- 포인트는 1원 단위 정수만 유통한다. 0.5 포인트가 유입되면 이후 모든 절사 규칙이 흔들린다.
    CONSTRAINT chk_point_accounts_integral
        CHECK (available = trunc(available) AND locked = trunc(locked) AND total = trunc(total))
);

-- ── point_lots ───────────────────────────────────────────────────────────────
-- 적립 1건 = 로트 1개. 사용은 로트를 FIFO 로 먹고, 소멸은 로트 단위로 일어난다.
CREATE TABLE point_lots (
    id                BIGSERIAL      PRIMARY KEY,
    account_id        BIGINT         NOT NULL REFERENCES point_accounts(id),
    origin            VARCHAR(24)    NOT NULL,
    original_amount   NUMERIC(19,2)  NOT NULL,
    remaining_amount  NUMERIC(19,2)  NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    granted_at        TIMESTAMPTZ    NOT NULL,
    expires_at        TIMESTAMPTZ,                 -- NULL = 무기한(수기 지급 등)
    reference_type    VARCHAR(50)    NOT NULL,
    reference_id      VARCHAR(100)   NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,

    -- 같은 근거로 로트가 두 번 발급되는 것을 DB 가 막는다(L3 멱등).
    CONSTRAINT uq_point_lots_natural UNIQUE (account_id, origin, reference_type, reference_id),
    CONSTRAINT chk_point_lots_origin
        CHECK (origin IN ('CHARGE_PRINCIPAL','CHARGE_BONUS','ORDER_EARN','MANUAL_GRANT','REFUND_RESTORE')),
    CONSTRAINT chk_point_lots_status
        CHECK (status IN ('ACTIVE','EXHAUSTED','EXPIRED','REVOKED')),
    CONSTRAINT chk_point_lots_original_positive     CHECK (original_amount > 0),
    CONSTRAINT chk_point_lots_remaining_non_negative CHECK (remaining_amount >= 0),
    CONSTRAINT chk_point_lots_remaining_le_original  CHECK (remaining_amount <= original_amount),
    CONSTRAINT chk_point_lots_expiry_after_grant
        CHECK (expires_at IS NULL OR expires_at > granted_at)
);

-- 사용 시 소비 순서 결정 + 소멸 배치 스캔. 만료 임박 순, NULL(무기한)은 마지막.
CREATE INDEX idx_point_lots_consume
    ON point_lots (account_id, expires_at NULLS LAST, id) WHERE status = 'ACTIVE';
CREATE INDEX idx_point_lots_expiring
    ON point_lots (expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ── point_entries (append-only) ──────────────────────────────────────────────
CREATE TABLE point_entries (
    id              BIGSERIAL      PRIMARY KEY,
    account_id      BIGINT         NOT NULL REFERENCES point_accounts(id),
    entry_type      VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19,2)  NOT NULL,
    reference_type  VARCHAR(50)    NOT NULL,
    reference_id    VARCHAR(100)   NOT NULL,
    sequence        INTEGER        NOT NULL DEFAULT 0,
    memo            VARCHAR(255),
    created_by      VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_point_entries_type
        CHECK (entry_type IN ('GRANT','USE','RESTORE','EXPIRE','REVOKE','LOCK','UNLOCK')),
    -- 금액은 언제나 양수. 방향은 entry_type 이 결정한다(deposit 과 동일 규약).
    CONSTRAINT chk_point_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT uq_point_entries_natural
        UNIQUE (account_id, entry_type, reference_type, reference_id, sequence)
);
CREATE INDEX idx_point_entries_account_created ON point_entries (account_id, created_at DESC);

-- ── point_lot_consumptions ───────────────────────────────────────────────────
-- 엔트리 1건이 어느 로트를 얼마나 소비했는지. 환불 복원이 "원래 그 로트"로 돌아가는 근거.
CREATE TABLE point_lot_consumptions (
    id        BIGSERIAL      PRIMARY KEY,
    entry_id  BIGINT         NOT NULL REFERENCES point_entries(id),
    lot_id    BIGINT         NOT NULL REFERENCES point_lots(id),
    amount    NUMERIC(19,2)  NOT NULL,

    CONSTRAINT uq_point_lot_consumptions UNIQUE (entry_id, lot_id),
    CONSTRAINT chk_point_lot_consumptions_positive CHECK (amount > 0)
);
CREATE INDEX idx_point_lot_consumptions_lot ON point_lot_consumptions (lot_id);

-- ── point_earn_policy (ADR 0032 구조 재사용) ─────────────────────────────────
-- 적립률을 코드 상수가 아니라 "기간을 가진 데이터"로 둔다. 표가 비면 적립 0 — 무행동 착지.
--
-- 주의: EXCLUDE 에 equality 열(scope, scope_key)을 섞으려면 btree_gist 가 필요하다.
-- settlement DB 에는 ADR 0032 가 이미 깔았지만 opslab 은 별도 DB 라 여기서 다시 선언해야 한다.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE point_earn_policy (
    id              BIGSERIAL    PRIMARY KEY,
    scope           VARCHAR(16)  NOT NULL,     -- GLOBAL | GRADE | CATEGORY
    scope_key       VARCHAR(64)  NOT NULL,     -- GLOBAL:'*' | GRADE:'VIP' ...
    earn_rate       NUMERIC(6,5) NOT NULL,     -- 0.01000 = 1%. Float 금지(ofDentis 반면교사 ①)
    validity_days   INTEGER      NOT NULL,     -- 적립분 유효기간
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    reason          VARCHAR(255) NOT NULL,
    created_by      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ,

    CONSTRAINT chk_pep_scope    CHECK (scope IN ('GLOBAL','GRADE','CATEGORY')),
    CONSTRAINT chk_pep_rate     CHECK (earn_rate >= 0 AND earn_rate <= 1),
    CONSTRAINT chk_pep_validity CHECK (validity_days > 0),
    CONSTRAINT chk_pep_range    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_pep_no_overlap EXCLUDE USING gist (
        scope     WITH =,
        scope_key WITH =,
        daterange(effective_from, COALESCE(effective_to, DATE '9999-12-31'), '[)') WITH &&
    )
);
```

`point_holds` 는 Phase 2(§10). `locked` 컬럼은 **처음부터** 둔다 — Phase 1 에서 항상 0 이지만,
나중에 추가하면 불변식 CHECK 를 갈아엎어야 하기 때문이다.

---

## 5. 상태머신

| 애그리거트 | 전이 |
| --- | --- |
| `PointAccount` | `ACTIVE → SUSPENDED → ACTIVE`, `ACTIVE|SUSPENDED → CLOSED`(잔액 0 일 때만) |
| `PointLot` | `ACTIVE → EXHAUSTED`(remaining=0) / `→ EXPIRED`(소멸 배치) / `→ REVOKED`(적립 취소) |
| `PointHold`(P2) | `ACTIVE → CAPTURED` / `→ RELEASED` / `→ EXPIRED` |

- `SUSPENDED` 계정은 **사용 불가, 적립은 가능**(부정거래 조사 중 적립까지 막으면 정상 주문이 손해).
- `EXPIRED`·`REVOKED` 로트는 되살리지 않는다. 되돌릴 일이 있으면 **신규 로트 발급**(역분개 원칙과 동일).

### 소비 순서 (사용 시)

`expires_at ASC NULLS LAST, id ASC` — 만료 임박분 우선. 동일 만료일이면 `id` 순.
**출처(origin)로 우선순위를 주지 않는다.** 보너스 우선 소진은 고객에게 불리하게 보이고,
동률 상황이 드물어 얻는 것도 없다.

### 환불 복원 규칙 (실무에서 늘 애매한 지점 — 명시한다)

주문 환불로 포인트를 되돌릴 때:

1. 원래 소비한 로트가 **아직 죽지 않았으면**(`ACTIVE` 또는 `EXHAUSTED`) 그 로트의 `remaining` 을
   되돌리고 `ACTIVE` 로 되살린다 — 유효기간이 원래대로 유지된다.
2. 로트가 이미 `EXPIRED`/`REVOKED` 면 **`REFUND_RESTORE` 출처로 새 로트를 발급**하고,
   유효기간은 **원 로트가 가졌던 기간과 같은 길이**를 지금부터 적용한다(무기한이었다면 무기한).

> **구현하며 바꾼 판단(2026-08-18).** 초안은 `EXHAUSTED` 도 신규 로트 대상으로 뒀고, 신규 로트에
> 정책 기본 유효기간을 주기로 했다. 둘 다 뒤집었다. 소진은 "죽음"이 아니라 "다 쓴 상태"이므로,
> 고객이 쓰지 않았더라면 그 로트는 여전히 원래 만료일을 가졌을 것이다 — 원 로트로 되돌리는 편이
> 정확하다. 그리고 신규 로트에 정책 기본값을 주면 원래보다 긴 유효기간을 덤으로 주는 셈이라,
> 원 로트의 기간 **길이**를 승계한다.

### 복원 대상 계정은 원장이 정한다

`RestorePointCommand` 에는 **userId 가 없다.** 환불은 언제나 **낸 사람**에게 돌아가야 하므로,
원 사용 엔트리에서 계정을 도출한다(`PointEntryPort.findAccountIdByReference`). 호출자가 넘긴
식별자를 믿으면 남의 계정으로 복원할 수 있다. 반대로 사용(use)은 결제 주체가 곧 소유자이므로
JWT 에서 파생한 userId 를 받는다.

---

## 6. 결제 연동 — 훅 2곳

기존 스텁 자리를 그대로 채운다. 새 분기를 만들지 않는다.

| 지점 | 현재 | 변경 |
| --- | --- | --- |
| `CreateSplitPaymentService.processTender()` (:110-114) | 로그만 | `PointTenderPort.use(userId, tenderAmount, "PAYMENT_TENDER", tenderId)` |
| `TenderRefundExecutor.refundTender()` (:83-86) | 로그만 | `PointTenderPort.restore(userId, portion, "PAYMENT_TENDER_REFUND", tenderId)` |

### 두 가지 걸림돌과 해법

**① `createSplit(orderId, tenderRequests)` 에 userId 가 없다.**
포인트 차감은 주체가 있어야 성립한다. 요청 DTO 에 userId 를 받는 건 **IDOR** 다 — 남의 포인트로
결제할 수 있다. 해법은 가드레일이 이미 정해 두었다: **JWT 주체에서 파생**한 뒤 주문 소유권과
대조하고, 불일치면 403. 즉 유스케이스 시그니처를 `createSplit(orderId, tenderRequests, actorUserId)`
로 넓히고, 컨트롤러가 `SecurityContext` 에서 채운다.

**② 멱등.** 환불 경로는 이미 `"tender-" + tenderId + "-" + portion` 형태의 안정 키를 쓴다
(`TenderRefundExecutor.java:78`). 포인트도 같은 규약을 따르되, 최종 방어선은
`uq_point_entries_natural` 이다 — 같은 tender 에 대한 중복 차감/복원이 **DB 에서** 막힌다.

**③ 포인트 100% 결제 — Phase 1 에서 하지 않았다.** 다른 단일 결제 경로
(`payment/application/CreatePaymentUseCase.java`)는 `paymentMethod` 문자열만 받고 **텐더를
모델링하지 않는다** — 여기에 포인트를 얹으면 텐더 개념을 두 번째로 만드는 셈이다. 계획은
`createSplit` 의 "최소 2개" 제약(:59)을 1개 이상으로 완화하고 이름을 `createWithTenders` 로
일반화하는 것이었으나, 그 제약은 기존 테스트가 명시적으로 고정하고 있어 별도 판단이 필요하다.
**Phase 1 은 제약을 그대로 두었고, 포인트 전액 결제는 여전히 불가능하다.** 남은 항목으로 §12 에
싣는다.

---

## 7. 적립·충전

| 사건 | 트리거 | 로트 origin |
| --- | --- | --- |
| 현금 충전 | 관리자/사용자 충전 결제 완료 | `CHARGE_PRINCIPAL` |
| 충전 보너스 | 같은 충전 트랜잭션 | `CHARGE_BONUS` |
| 구매 적립 | 주문 확정(구매확정/배송완료) | `ORDER_EARN` |
| 수기 지급 | 관리자 콘솔(사유 필수) | `MANUAL_GRANT` |

- **충전 보너스는 반드시 별도 로트다.** 같은 로트에 합치면 GL 계정(현금 vs 판촉비)이 섞여
  분개를 만들 수 없고, 환불 시 "보너스만 회수"가 불가능해진다.
- 적립액 계산: `주문금액 × earn_rate` 를 `setScale(0, RoundingMode.DOWN)` — **원 미만 절사**.
  `chk_*_integral` 이 이를 DB 에서 재확인한다.
- 적립률·유효기간의 출처는 `point_earn_policy` 뿐이다. 등급 enum 에 `pointRate` 필드를 다는 방식은
  쓰지 않는다(ofDentis 반면교사 ① — `Float pointRate`).

---

## 8. 소멸(만료) 배치

- 일 1회, `expires_at < now()` 인 `ACTIVE` 로트를 `EXPIRED` 로 닫고 `remaining` 만큼 `available` 차감.
- 사전 안내를 위한 "소멸 예정" 조회 API 를 함께 낸다(D-30/D-7).
- **ShedLock 이름은 유일해야 한다.** ofDentis 의 실장애가 정확히 이 지점이었다 —
  `PointScheduler` 의 서로 다른 5개 메서드가 락 이름 2개를 나눠 써서 배치가 서로를 굶겼다
  (`dentis-architec.md` §3-③). 락 이름은 `pointLotExpiry` 단일 용도로 고정한다.
- 관리자 실행은 **dryRun 기본 true**(P0-3 프로토콜, `/admin/payment-expiry/run` 과 동일 규약).

---

## 9. 회계 연계 — GL 매핑

`account-service` 는 **소비 전용**이므로, 발행은 order 의 Outbox 가 한다.

### 신설 계정 (`GlAccount`)

| 계정 | 방향 | 성격 |
| --- | --- | --- |
| `POINT_LIABILITY` | CREDIT | 고객 포인트 선수금 — 미사용 포인트는 회사의 부채다 |
| `POINT_PROMOTION_EXPENSE` | DEBIT | 보너스·구매적립의 상대계정(판촉비) |
| `POINT_BREAKAGE_INCOME` | CREDIT | 소멸이익(breakage) |

`OwnerType` 에 `CUSTOMER`(ownerId = userId) 추가.

### 분개표

| 소비 토픽 | 분개 | 비고 |
| --- | --- | --- |
| `lemuel.point.charged` | DR `CASH` / CR `POINT_LIABILITY` | 현금 충전 원금 |
| `lemuel.point.granted` | DR `POINT_PROMOTION_EXPENSE` / CR `POINT_LIABILITY` | 보너스·구매적립·수기지급 |
| `lemuel.point.used` | DR `POINT_LIABILITY` / CR `CASH` | **핵심 — 아래 설명** |
| `lemuel.point.restored` | DR `CASH` / CR `POINT_LIABILITY` | 환불 복원(used 의 대칭) |
| `lemuel.point.expired` | DR `POINT_LIABILITY` / CR `POINT_BREAKAGE_INCOME` | 소멸 |

**`point.used` 가 CR `CASH` 인 이유가 이 설계의 핵심이다.**
`settlement.created` 는 이미 `DR CASH / CR SELLER_PAYABLE` 로 전기된다 — 주문금액만큼 현금이
들어왔다고 가정한다(`AccountEntry.settlementCreatedImmediate`). 그런데 포인트로 결제된 부분은
**그 시점에 현금이 들어오지 않았다**(충전 시점에 이미 들어왔다). `point.used` 의 CR `CASH` 가
그 가공의 현금 유입을 정확히 상계한다.

결과적으로 **settlement 은 포인트를 몰라도 되고, 기존 분개 매핑을 한 줄도 고치지 않는다.**
`payment.captured` 페이로드에 텐더 구성이 없다는 사실
(`lemuel.payment.captured.schema.json` — `amount` 단일 필드)이 오히려 이 설계와 정합한다.

매핑은 예외 없이 `AccountEntry` 정적 팩토리에만 둔다(팩토리 우회 금지).

---

## 10. 이벤트 계약 5종

토픽명은 Outbox 규약에서 자동 도출된다 — `aggregateType="Point"` + `eventType="PointCharged"`
→ `lemuel.point.charged` (`KafkaOutboxPublisher.resolveTopic`, :115-123).

| 토픽 | 페이로드 |
| --- | --- |
| `lemuel.point.charged` | userId, lotId, amount, chargePaymentId, occurredAt |
| `lemuel.point.granted` | userId, lotId, amount, origin, referenceType, referenceId, expiresAt |
| `lemuel.point.used` | userId, entryId, amount, referenceType, referenceId, consumedLots[] |
| `lemuel.point.restored` | userId, entryId, amount, referenceType, referenceId |
| `lemuel.point.expired` | userId, lotId, amount, expiredAt |

- 금액은 전부 `BigDecimal.toPlainString()` 문자열(기존 계약 관례 — 정밀도 보존).
- 5종 모두 `topic-catalog.json` 등록 필수. 누락하면 `kafka-topic-gate` 가 CI 에서 FAIL 한다.
- 스키마 + 정본 샘플은 `shared-common/src/testFixtures/resources/contracts/events/` 에 두고
  양방향 계약 테스트를 배선한다(ADR 0024, `event-contract-change` 스킬 절차).

---

## 11. 열어둔 결정 (오너 확정 필요)

| # | 결정 | 기본안 |
| --- | --- | --- |
| 1 | 충전 보너스율을 `point_earn_policy` 에 합칠 것인가, 별도 `point_charge_bonus_policy` 로 뗄 것인가 | **별도 테이블.** 적립(주문 기반)과 충전 보너스(금액 구간 기반)는 산정 축이 다르다 |
| 2 | 포인트 사용 상한(주문금액의 N%) 정책을 Phase 1 에 넣을 것인가 | **넣지 않는다.** 상한은 프로모션 정책이지 원장 규칙이 아니다 |
| 3 | 소멸이익을 인식할 것인가, 부채로 계속 이월할 것인가 | **인식(`POINT_BREAKAGE_INCOME`).** 이월은 부채가 무한히 쌓여 시산표가 의미를 잃는다 |

---

## 12. Phase 로드맵

| Phase | 범위 | 완료 판정 |
| --- | --- | --- |
| 1 | 계정·로트·엔트리·소비상세 + 사용/복원 훅 2곳 + 적립정책 + 소멸배치 + 이벤트 5종 발행 | `:order-service:test` + JaCoCo LINE 90%, 스키마 게이트 GREEN |

**Phase 1 착지 상태 (2026-08-18)**

| 항목 | 상태 |
| --- | --- |
| 계정·로트·엔트리·소비상세 + 도메인 불변식 | 완료 |
| 사용/복원 훅 2곳 + JWT 주체 파생(IDOR) | 완료 |
| 적립률 정책(ADR 0032 구조) + 소멸 배치 + 관리자 콘솔 | 완료 |
| 이벤트 5종 발행 + 토픽 카탈로그 등록 | 완료 |
| GL 3계정 + 분개 매핑 + 컨슈머 + CHECK 마이그레이션 | 완료 |
| 계약 스키마·정본 샘플 + 양방향 계약 테스트(ADR 0024) | 완료 |

**아직 하지 않은 것**(정직하게 남긴다):

- **포인트 전액 결제**(§6 ③) — 분할결제 최소 2텐더 제약을 그대로 두었다.
- **구매 적립 트리거** — `PointEarnPolicy`·`GrantPointUseCase` 는 있으나 주문 확정 시점에
  자동으로 부르는 배선이 없다. 현재 적립은 관리자 수기 지급 경로로만 일어난다.
- **GIFT_CARD 텐더** — 여전히 원장이 없다. 포인트와 똑같은 구멍이며, 코드와 테스트에
  드러내 두었다(`createSplit_giftCardHasNoLedger`).
| 2 | `point_holds` — 가상계좌 동반 결제의 포인트 선점, 미입금 자동만료(P1-4)와 연동 해제 | 동시성 IT(입금 vs 만료 경합) |
| 3 | 관리자 콘솔 `/admin/points` — 잔액·원장 조회, 수기 지급/차감(사유 필수+감사로그), 소멸예정, 정책 편집 | 라우트 + 메뉴 2스텝(`menu-route-gate`) |

**Phase 2 가 필요한 이유**를 미리 적어 둔다: `VIRTUAL_ACCOUNT` 는 입금 전까지 결제가 확정되지
않는데, 그 사이 포인트를 차감해 두지 않으면 같은 포인트를 다른 주문에 또 쓸 수 있다. 반대로
차감해 버리면 미입금 취소 시 복원 경로가 필요하다. `locked` 로 선점하고, 이미 구현된 미입금
자동 만료가 해제 트리거를 겸하는 것이 가장 적은 부품으로 푸는 방법이다.

---

## 13. 무행동 착지

`point_earn_policy` 가 비어 있으면 적립률 해석이 0 이라 **적립이 일어나지 않는다.**
로트가 없으면 잔액이 0 이고, 잔액 0 이면 포인트 텐더는 잔액 부족으로 거부된다.
즉 **정책을 넣기 전까지 이 기능은 "포인트 결제 시도를 정확히 거부하는 것" 외에 아무 일도 하지
않는다.** 지금처럼 검증 없이 통과시키는 것보다 안전하고, 도입 자체가 기존 주문 흐름을 바꾸지 않는다.
