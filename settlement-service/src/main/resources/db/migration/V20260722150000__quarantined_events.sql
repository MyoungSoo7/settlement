-- P0-3 이벤트 격리 완결 (seed-p0-3, AC-6/AC-7)
-- at-least-once 소비의 "조용한 유실" 경로(event_id 누락·불량 UUID·payload 계약 위반)를
-- DB 격리 추적으로 대체한다. DLT(.DLT 토픽) 격리와 공존하는 추적 계층 — 대체가 아니다.

CREATE TABLE quarantined_events (
    id              BIGSERIAL PRIMARY KEY,
    consumer_group  VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    kafka_partition INT          NOT NULL,
    kafka_offset    BIGINT       NOT NULL,
    event_id        UUID         NULL,             -- MISSING/INVALID_EVENT_ID 면 NULL
    cause           VARCHAR(30)  NOT NULL,         -- MISSING_EVENT_ID | INVALID_EVENT_ID | INVALID_PAYLOAD
    cause_detail    TEXT         NULL,             -- 불량 헤더 원문·예외 메시지 (증거)
    payload         TEXT         NULL,             -- 원본 payload 보존 (증거·재처리 원본)
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',  -- NEW | REPLAYED
    occurred_at     TIMESTAMP    NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMP    NULL,
    replay_event_id UUID         NULL,             -- 재처리 시 부여/사용된 event_id
    -- 같은 불량 레코드의 재전달(at-least-once)이 중복 행을 만들지 않게 하는 멱등 키
    CONSTRAINT uq_quarantined_record UNIQUE (consumer_group, topic, kafka_partition, kafka_offset)
);

CREATE INDEX idx_quarantined_status ON quarantined_events (status, occurred_at DESC);

-- 이미 처리된 event_id 의 재도착(중복) 추적 — 3분류(PROCESSED/DUPLICATE/QUARANTINED) 조회용.
-- 중복은 드문 사건이라 행 단위 upsert 로도 처리량을 훼손하지 않는다.
CREATE TABLE duplicate_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    hit_count      BIGINT       NOT NULL DEFAULT 1,
    first_seen_at  TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
