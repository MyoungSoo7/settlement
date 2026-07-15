-- V20260717100100: outbox_events / processed_events 리텐션 함수 + 정리 인덱스 — 감사 하드닝 대칭 복원 (organization)
--
-- [왜] 전 서비스 하드닝 캠페인(V20260715200005/V20260716300200 계열)에서 organization-service 만
--   리텐션 함수·최소 보존 가드가 누락됐다. outbox PUBLISHED 행·processed_events 는 무한 적재되면
--   폴러 스캔·인덱스 비대·볼륨 부담이 누적된다.
-- [시그니처] 전 서비스 통일 규약: prune_*(p_retention INTERVAL DEFAULT ...) RETURNS BIGINT(삭제 행 수).
-- [파티셔닝 안 하는 이유] outbox_events.event_id 전역 UNIQUE(uq_organization_outbox_event_id)와
--   processed_events (consumer_group, event_id) PK 는 멱등 방어선 — 파티션 키를 포함시키면 전역
--   유일성이 깨진다. 파티션 대신 리텐션 함수로 관리한다.
-- [최소 보존 가드] p_retention 이 7일 미만이면 거부 — Kafka 재전송 창 내에 멱등키를 선삭제하면
--   리플레이 이중 처리가 발생한다. 보존기간은 반드시 브로커 보존기간 + 여유 이상으로.

-- PUBLISHED outbox 정리 스캔용 부분 인덱스 (V2 의 기존 인덱스는 PENDING/FAILED 전용이라 미커버)
CREATE INDEX IF NOT EXISTS idx_organization_outbox_published_prune
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- 발행 완료(PUBLISHED) outbox 행을 보존기간(p_retention) 초과분만 삭제. 반환=삭제 행 수.
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
