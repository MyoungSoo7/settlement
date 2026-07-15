-- V20260716301000: prune_processed_events 최소 보존 하한 가드(≥7일) — 크로스컷 DB 리뷰 F3 (settlement)
--
-- [설계 근거]
--   processed_events 는 (consumer_group, event_id) 멱등 방어선이다. Kafka 재전송 창 안의 event_id 를 성급히
--   지우면 재도착한 동일 이벤트가 "처음 본 것"으로 오인돼 이중 처리(이중 기표·이중 정산)로 이어진다. 리텐션은
--   재전송 최대 지연보다 커야 하므로, 실수로 짧은 값을 넘겨도 DB 가 거부하도록 하한 7일 가드를 추가한다.
--   삭제 로직·시그니처(public 스키마, p_retention 파라미터, DEFAULT 30일)는 기존(V20260715110700)과 동일 —
--   기존 비음수 가드를 7일 하한으로 강화한 것뿐이다.

CREATE OR REPLACE FUNCTION public.prune_processed_events(p_retention interval DEFAULT interval '30 days')
RETURNS bigint AS $$
DECLARE
    deleted_count bigint;
BEGIN
    IF p_retention IS NULL OR p_retention < interval '7 days' THEN
        RAISE EXCEPTION 'p_retention 은 최소 7일 이상이어야 합니다(재전송 창 내 멱등키 조기 삭제 → 이중 처리 방지). got=%', p_retention;
    END IF;
    DELETE FROM public.processed_events
     WHERE processed_at < now() - p_retention;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION public.prune_processed_events(interval) IS
    'processed_at 이 p_retention 경과한 멱등 처리 이력 삭제(반환=삭제 건수 bigint). p_retention 하한 7일(재전송 창 내 멱등키 보호).';
