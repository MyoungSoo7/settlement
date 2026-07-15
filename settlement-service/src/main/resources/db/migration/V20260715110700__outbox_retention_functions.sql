-- V20260715110700: outbox/processed_events 리텐션(정리) 함수 + 지원 인덱스 — DB 설계 리뷰 후속 (레인 E1)
--
-- 무엇을: 오래된 PUBLISHED outbox 행과 오래된 processed_events 행을 지우는 SQL 함수 2종과, 그 정리
--         스캔을 받쳐줄 인덱스. 실제 호출(스케줄러/운영 잡)은 별도 — 여기서는 함수·인덱스만 배선한다.
-- 왜: outbox_events(PUBLISHED)·processed_events 는 발행/멱등 처리 후에도 무한 누적된다. 파티셔닝 없이
--     주기적 프루닝으로 테이블 비대화를 막는다.
--
-- ★ 시그니처(레인 간 통일): 파라미터는 보존 기간 INTERVAL, 반환은 BIGINT(삭제 행 수). E4 가 먼저 확정한
--     규격에 맞춘다 — prune_outbox_published(interval) / prune_processed_events(interval) RETURNS bigint.
--     (int days 가 아니라 INTERVAL — 호출측이 '30 days'·'12 hours' 등 유연히 지정.)
--
-- ★ 파티셔닝을 하지 않는 이유(설계 결정): outbox_events.event_id 는 전역 UNIQUE 이고 이것이 컨슈머측
--     멱등의 최종 방어선이다. PG 의 파티션 테이블은 UNIQUE 제약에 파티션 키(예: created_at)를 포함할 것을
--     요구하므로, 시간 기준 레인지 파티셔닝을 하면 event_id 전역 유니크를 유지할 수 없거나(파티션별
--     유니크로 약화) 비용이 커진다. 따라서 단일 테이블 + 시간 스코프 프루닝을 택해 전역 유니크를 보존한다.
--
-- ★ retain 주의(멱등 창): processed_events 를 지우면 그만큼 멱등 판정 창이 닫힌다. Kafka 재전송/
--     지연 재처리 최대 지연보다 retain 을 크게 잡아야 과거 중복 이벤트가 다시 처리되지 않는다.

-- 지원 인덱스 --------------------------------------------------------------------
-- PUBLISHED 중 published_at 경과분 스캔용 부분 인덱스(정리 대상만).
CREATE INDEX IF NOT EXISTS idx_outbox_published_prune
    ON public.outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- processed_events 경과분 스캔용.
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at
    ON public.processed_events (processed_at);

-- 정리 함수 ----------------------------------------------------------------------
-- 시그니처는 전 서비스 통일 규약: prune_*(p_retention INTERVAL DEFAULT ...) RETURNS bigint.
-- 발행 완료(PUBLISHED) 이후 p_retention 을 넘긴 outbox 행 삭제. 삭제 건수(bigint)를 반환.
CREATE OR REPLACE FUNCTION public.prune_outbox_published(p_retention interval DEFAULT interval '7 days')
RETURNS bigint AS $$
DECLARE
    deleted_count bigint;
BEGIN
    IF p_retention IS NULL OR p_retention < interval '0' THEN
        RAISE EXCEPTION 'p_retention must be a non-negative interval (got %)', p_retention;
    END IF;
    DELETE FROM public.outbox_events
     WHERE status = 'PUBLISHED'
       AND published_at IS NOT NULL
       AND published_at < now() - p_retention;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- processed_at 이 p_retention 을 넘긴 멱등 처리 이력 삭제. 삭제 건수(bigint)를 반환.
-- (p_retention 은 반드시 재전송 최대 지연보다 크게 — 위 주석의 멱등 창 참조.)
CREATE OR REPLACE FUNCTION public.prune_processed_events(p_retention interval DEFAULT interval '30 days')
RETURNS bigint AS $$
DECLARE
    deleted_count bigint;
BEGIN
    IF p_retention IS NULL OR p_retention < interval '0' THEN
        RAISE EXCEPTION 'p_retention must be a non-negative interval (got %)', p_retention;
    END IF;
    DELETE FROM public.processed_events
     WHERE processed_at < now() - p_retention;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION public.prune_outbox_published(interval) IS
    'PUBLISHED 이후 p_retention 경과 outbox 행 삭제(반환=삭제 건수 bigint). event_id 전역 유니크는 보존.';
COMMENT ON FUNCTION public.prune_processed_events(interval) IS
    'processed_at 이 p_retention 경과한 멱등 처리 이력 삭제(반환=삭제 건수 bigint). p_retention > 재전송 최대 지연.';
