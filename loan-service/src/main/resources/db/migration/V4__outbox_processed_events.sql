-- V4: loan-service 자체 DB(lemuel_loan) 용 Outbox / 멱등 추적 테이블
--
-- loan-service 는 DB-per-service 이므로 order/settlement 의 공유 DB(opslab) 에 있는
-- outbox_events / processed_events 를 쓸 수 없다. shared-common 의 Outbox·멱등 인프라가
-- 매핑하는 동일 스키마를 자체 DB 에 직접 생성한다.
--
-- (order-service V28/V29 와 동일 스키마. 단 schema prefix 는 public — 자체 DB.)

-- Transactional Outbox: 도메인 트랜잭션 커밋과 외부 이벤트 발행의 원자성 보장.
CREATE TABLE IF NOT EXISTS outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,      -- 예: "LoanAdvance"
    aggregate_id      VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(100) NOT NULL,      -- 예: "LoanDisbursementRequested", "LoanRepaymentApplied"
    event_id          UUID         NOT NULL,      -- 전역 고유 — 컨슈머 측 멱등 키
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / FAILED
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP,
    trace_parent      VARCHAR(64),
    -- 멀티워커 claim(리스) 컬럼 — OutboxPublisherScheduler 의 FOR UPDATE SKIP LOCKED claim.
    -- ★ shared-common ClaimOutboxEventPort 네이티브 쿼리가 claimed_at/claimed_by 를 직접 참조하므로 필수.
    claimed_at        TIMESTAMP,
    claimed_by        VARCHAR(64),

    CONSTRAINT chk_loan_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 폴러 조회용: PENDING/FAILED + 오래된 순
CREATE INDEX IF NOT EXISTS idx_loan_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- 컨슈머 멱등(프로듀서 측 중복 발행 방지)
CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_loan_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

-- claim 후보 조회 최적화: PENDING 행을 created_at 순으로, claimed_at(리스) 필터와 함께
CREATE INDEX IF NOT EXISTS idx_loan_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부 기록.
-- loan 은 SettlementCreated / SettlementConfirmed 를 at-least-once 로 수신하므로 필수.
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_loan_processed_events_processed_at
    ON processed_events (processed_at);
