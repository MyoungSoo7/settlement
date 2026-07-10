-- V2: 컨슈머 멱등 추적 테이블 (account-service 자체 DB lemuel_account)
--
-- account-service 는 DB-per-service 이며 이벤트 발행이 없다(소비 전용) → outbox_events 불필요.
-- shared-common 의 멱등 컨슈머 골격(IdempotentEventConsumer)이 매핑하는 processed_events 만 자체 DB 에 둔다.
-- (loan-service V4 의 processed_events 부분과 동일 스키마.)

CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_account_processed_events_processed_at
    ON processed_events (processed_at);
