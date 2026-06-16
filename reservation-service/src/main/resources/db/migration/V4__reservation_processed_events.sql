-- V4: 컨슈머 멱등 테이블 (reservation-service 자체 소유 — DB-per-service)
--
-- user-service 멤버십 이벤트를 consume 할 때 (consumer_group, event_id) 로 중복 처리를 막는다.
-- shared-common 의 processed_events 와 동형이지만 reservation 자체 DB 에 둔다.

CREATE TABLE IF NOT EXISTS reservation.processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100),
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);
