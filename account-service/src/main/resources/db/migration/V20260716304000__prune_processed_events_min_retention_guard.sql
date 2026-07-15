-- V20260716304000: prune_processed_events 최소 보존 하한 가드(≥7일) — 크로스컷 DB 리뷰 F3 (account)
--
-- [설계 근거]
--   processed_events 는 (consumer_group, event_id) 멱등 방어선이다. 계정계는 loan·investment·settlement 이벤트를
--   소비만 하는 원천 GL 원장이라(발행 없음) 멱등 오작동은 곧 이중 기표다. Kafka 재전송 창 안의 event_id 를
--   성급히 지우면 재도착한 동일 이벤트가 "처음 본 것"으로 오인돼 이중 처리로 이어지므로, 리텐션이 재전송 최대
--   지연보다 짧아지지 않도록 하한 7일 가드를 추가한다. 삭제 로직·시그니처(무접두 스키마, p_retention,
--   DEFAULT 30일)는 기존(V20260715141000)과 동일 — 가드만 신규 추가한다.

CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    IF p_retention IS NULL OR p_retention < INTERVAL '7 days' THEN
        RAISE EXCEPTION 'p_retention 은 최소 7일 이상이어야 합니다(재전송 창 내 멱등키 조기 삭제 → 이중 기표 방지). got=%', p_retention;
    END IF;
    DELETE FROM processed_events
        WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_processed_events(INTERVAL) IS
    'processed_events 멱등 추적 행 리텐션 정리(processed_at 기준). p_retention 하한 7일(재전송 창 내 멱등키 보호). 삭제 건수 반환.';
