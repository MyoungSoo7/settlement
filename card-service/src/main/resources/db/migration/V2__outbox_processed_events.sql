-- V2: card-service 자체 DB(lemuel_card) 용 Outbox / 멱등 추적 테이블 + ShedLock
--
-- DB-per-service 이므로 공유 DB 의 outbox_events/processed_events 를 쓸 수 없다.
-- shared-common 의 Outbox·멱등 인프라가 매핑하는 동일 스키마를 자체 DB 에 직접 생성한다
-- (organization V2 / investment V2 / loan V4 와 동일 스키마).
--
-- ★ organization 은 이벤트 봉투 표준 컬럼(occurred_at/event_version/producer, DATA-STANDARD N4)을
--   V20260728010000 에서 나중에 백필로 얹었지만, card 는 신규 서비스라 처음부터(occurred_at NOT NULL,
--   백필 UPDATE 불필요) 포함한다. 리텐션 함수(prune_outbox_published/prune_processed_events)와
--   ShedLock 테이블도 organization 의 하드닝 캠페인 최종 상태를 기준으로 처음부터 포함한다.

CREATE TABLE IF NOT EXISTS outbox_events (
    id                BIGSERIAL PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,      -- 예: "CardAccount"
    aggregate_id      VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(100) NOT NULL,      -- 예: "CardAccountOpened"
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
    -- 이벤트 봉투 표준 필드(DATA-STANDARD N4). occurred_at 은 사건이 *실제로* 일어난 시각(UTC) —
    -- created_at(행 생성 시각)과 달리 재처리·백필로도 원래 사건 시각을 잃지 않는다.
    -- event_version 은 봉투/페이로드 스키마 버전(소비측 버전 분기 근거). producer 는 발행 서비스명.
    occurred_at       TIMESTAMPTZ  NOT NULL,
    event_version     INTEGER      NOT NULL DEFAULT 1,
    producer          VARCHAR(64),

    CONSTRAINT chk_card_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_card_outbox_status_created
    ON outbox_events (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE UNIQUE INDEX IF NOT EXISTS uq_card_outbox_event_id
    ON outbox_events (event_id);

CREATE INDEX IF NOT EXISTS idx_card_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS idx_card_outbox_pending_claim
    ON outbox_events (created_at, claimed_at)
    WHERE status = 'PENDING';

-- 사건 시각 기준 조회(지연 측정·기간별 재처리)를 위한 인덱스.
CREATE INDEX IF NOT EXISTS idx_card_outbox_occurred_at
    ON outbox_events (occurred_at);

-- PUBLISHED outbox 정리 스캔용 부분 인덱스 (위 idx_card_outbox_status_created 는 PENDING/FAILED 전용이라 미커버)
CREATE INDEX IF NOT EXISTS idx_card_outbox_published_prune
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    processed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX IF NOT EXISTS idx_card_processed_events_processed_at
    ON processed_events (processed_at);

-- 발행 완료(PUBLISHED) outbox 행을 보존기간(p_retention) 초과분만 삭제. 반환=삭제 행 수.
-- 최소 보존 가드: p_retention 이 7일 미만이면 거부 — Kafka 재전송 창 내에 멱등키를 선삭제하면
-- 리플레이 이중 처리가 발생한다. 보존기간은 반드시 브로커 보존기간 + 여유 이상으로.
CREATE OR REPLACE FUNCTION prune_outbox_published(p_retention INTERVAL DEFAULT INTERVAL '7 days')
RETURNS BIGINT
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention IS NULL OR p_retention < INTERVAL '7 days' THEN
        RAISE EXCEPTION 'p_retention 은 7일 이상이어야 합니다 (요청: %) — Kafka 재전송 창 내 선삭제 방지 가드', p_retention;
    END IF;
    DELETE FROM outbox_events
     WHERE status = 'PUBLISHED'
       AND published_at IS NOT NULL
       AND published_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

-- 처리 완료 멱등 추적(processed_events)을 보존기간(p_retention) 초과분만 삭제. 반환=삭제 행 수.
CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention IS NULL OR p_retention < INTERVAL '7 days' THEN
        RAISE EXCEPTION 'p_retention 은 7일 이상이어야 합니다 (요청: %) — 멱등키 선삭제 → 리플레이 이중 처리 방지 가드', p_retention;
    END IF;
    DELETE FROM processed_events
     WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

COMMENT ON FUNCTION prune_outbox_published(interval) IS
    'PUBLISHED 이후 p_retention 경과 outbox 행 삭제(반환=삭제 건수). 최소 7일 가드. event_id 전역 유니크는 보존.';
COMMENT ON FUNCTION prune_processed_events(interval) IS
    'processed_at 이 p_retention 경과한 멱등 처리 이력 삭제(반환=삭제 건수). 최소 7일 가드 — 브로커 보존기간+여유 이상으로 호출할 것.';

-- ShedLock — @Scheduled 분산 락 테이블. replicas N 개 중 1 개만 실행 보장.
-- 현재 prod 는 replicas: 1 이라 미래 HA 대비 (일 1회 한도 재산정, Task 13 이 사용).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

COMMENT ON TABLE shedlock IS 'ShedLock distributed lock — @SchedulerLock annotation 의 backing store';
