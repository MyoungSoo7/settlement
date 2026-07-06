-- V4: company-service 자체 DB(lemuel_company) 용 Outbox / 멱등 추적 테이블 (ADR 0023 Phase 3)
--
-- company-service 는 DB-per-service 이므로 shared-common 의 Outbox·멱등 인프라가 매핑하는 동일
-- 스키마를 자체 DB(public)에 직접 생성한다. (loan-service V4 와 동일 스키마 — 단 schema 는 public.)
-- OutboxSchema 는 hibernate.default_schema(미설정 → public)를 읽어 네이티브 SKIP LOCKED 쿼리의
-- 스키마 한정자로 쓴다.

-- Transactional Outbox: 도메인 트랜잭션 커밋과 외부 이벤트 발행의 원자성 보장.
CREATE TABLE IF NOT EXISTS outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,      -- 예: "Company"
    aggregate_id      VARCHAR(64)  NOT NULL,      -- 종목코드
    event_type        VARCHAR(100) NOT NULL,      -- 예: "CompanyReputationChanged"
    event_id          UUID         NOT NULL,      -- 전역 고유 — 컨슈머 측 멱등 키
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PUBLISHED / FAILED
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMP,
    trace_parent      VARCHAR(64),
    -- 멀티워커 claim(리스) 컬럼 — OutboxPublisherScheduler 의 FOR UPDATE SKIP LOCKED claim.
    claimed_at        TIMESTAMP,
    claimed_by        VARCHAR(64),

    CONSTRAINT chk_company_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_company_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_company_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_company_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_company_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

-- 컨슈머 측 멱등 추적: (consumer_group, event_id) 단위 처리 여부.
-- (company 는 Phase 3 에서 발행만 하지만, shared-common 의 ProcessedEventRepository 가 스캔되므로
--  DDL 을 함께 생성해 향후 자체 컨슈머 도입 시 재사용한다.)
CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_company_processed_events_processed_at
    ON processed_events (processed_at);
