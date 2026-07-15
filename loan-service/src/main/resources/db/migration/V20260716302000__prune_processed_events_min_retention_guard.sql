-- V20260716302000: prune_processed_events 최소 보존 하한 가드(≥7일) — 크로스컷 DB 리뷰 F3 (loan)
--
-- [설계 근거]
--   processed_events 는 (consumer_group, event_id) 멱등 방어선이다. Kafka 재전송 창 안의 event_id 를 성급히
--   지우면 재도착한 동일 이벤트가 "처음 본 것"으로 오인돼 이중 처리(이중 기표·이중 정산)로 이어진다. 리텐션은
--   재전송 최대 지연보다 커야 하므로, 실수로 짧은 값을 넘겨도 DB 가 거부하도록 하한 7일 가드를 추가한다.
--   삭제 로직·시그니처(무접두 스키마, p_retention 파라미터, DEFAULT 30일)는 기존(V20260715140000)과 동일 —
--   가드만 신규 추가한다.

CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention IS NULL OR p_retention < INTERVAL '7 days' THEN
        RAISE EXCEPTION 'p_retention 은 최소 7일 이상이어야 합니다(재전송 창 내 멱등키 조기 삭제 → 이중 처리 방지). got=%', p_retention;
    END IF;
    DELETE FROM processed_events
        WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_processed_events(INTERVAL) IS
    'processed_events 멱등 추적 행 리텐션 정리(processed_at 기준). p_retention 하한 7일(재전송 창 내 멱등키 보호). 반환=삭제 건수.';
