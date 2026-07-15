-- V20260716200100: 운영성 큐 리텐션(정리) 함수 2종 + 지원 인덱스 — DB 설계 리뷰 R2 후속 (레인 F2)
--
-- 무엇을: 처리 완료된 ledger_outbox / settlement_index_queue 행을 지우는 SQL 함수 2종과, 정리 스캔용
--         부분 인덱스. 실제 호출(스케줄러/운영 잡)은 별도 — 여기서는 함수·인덱스만 배선한다.
-- 왜: 두 큐 모두 처리 완료 후에도 무한 누적된다(V20260715110700 이 outbox_events/processed_events 에
--     한 것과 동일 문제). 파티셔닝 없이 주기적 프루닝으로 테이블 비대화를 막는다.
--
-- ★ 시그니처(전 서비스 통일 규약, V20260715110700 과 동일): prune_*(p_retention interval DEFAULT ...)
--     RETURNS bigint(삭제 행 수). p_retention 음수/NULL 은 RAISE EXCEPTION.
--
-- ★ "처리 완료" 판정 근거(V1 스키마·엔티티 대조):
--   1) ledger_outbox: 상태 enum 은 LedgerOutboxStatus = PENDING → DONE(성공) / FAILED(재시도 한도 초과).
--      SpringDataLedgerOutboxRepository.markDone 이 `status='DONE', processed_at=CURRENT_TIMESTAMP` 로
--      마킹한다. → 성공 처리분(status='DONE') 만 processed_at 기준으로 프루닝한다.
--      FAILED(영구 실패)는 원장 작업 유실이라는 금융 이슈의 포렌식 근거이므로 지우지 않는다
--      (outbox 리텐션이 PUBLISHED 만 지우고 FAILED 를 남기는 것과 동일한 판단).
--   2) settlement_index_queue: ES 재색인 실패 재시도 큐. 엔티티는 status(기본 'PENDING')·processed_at 을
--      갖지만, 현재 코드베이스에는 이 큐를 드레인해 특정 status 리터럴('DONE' 등)로 마킹하는 프로세서가
--      아직 없다(enqueue 만 존재). 따라서 "완료" 의 권위 있는 신호는 status 리터럴이 아니라
--      processed_at IS NOT NULL(재색인 성공 시각 기록)이다. → processed_at 기준으로만 프루닝하고
--      미처리(processed_at NULL, 재시도 대기/소진 전) 행은 절대 건드리지 않는다.
--
-- ★ retain 주의: 두 큐 모두 "완료 시각(processed_at)" 이 retention 을 넘긴 행만 삭제하므로, 진행 중·
--     재시도 대기 행은 retention 값과 무관하게 보존된다(운영 안전).

-- 지원 인덱스 --------------------------------------------------------------------
-- ledger_outbox: DONE 중 processed_at 경과분 스캔용 부분 인덱스(정리 대상만).
CREATE INDEX IF NOT EXISTS idx_ledger_outbox_done_prune
    ON public.ledger_outbox (processed_at)
    WHERE status = 'DONE';

-- settlement_index_queue: 완료(processed_at 기록됨) 경과분 스캔용 부분 인덱스.
CREATE INDEX IF NOT EXISTS idx_index_queue_processed_prune
    ON public.settlement_index_queue (processed_at)
    WHERE processed_at IS NOT NULL;

-- 정리 함수 ----------------------------------------------------------------------
-- 성공 처리(DONE) 이후 p_retention 을 넘긴 ledger_outbox 행 삭제. 삭제 건수(bigint)를 반환.
CREATE OR REPLACE FUNCTION public.prune_ledger_outbox(p_retention interval DEFAULT interval '7 days')
RETURNS bigint AS $$
DECLARE
    deleted_count bigint;
BEGIN
    IF p_retention IS NULL OR p_retention < interval '0' THEN
        RAISE EXCEPTION 'p_retention must be a non-negative interval (got %)', p_retention;
    END IF;
    DELETE FROM public.ledger_outbox
     WHERE status = 'DONE'
       AND processed_at IS NOT NULL
       AND processed_at < now() - p_retention;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- 재색인 성공(processed_at 기록) 이후 p_retention 을 넘긴 settlement_index_queue 행 삭제. 삭제 건수 반환.
CREATE OR REPLACE FUNCTION public.prune_settlement_index_queue(p_retention interval DEFAULT interval '7 days')
RETURNS bigint AS $$
DECLARE
    deleted_count bigint;
BEGIN
    IF p_retention IS NULL OR p_retention < interval '0' THEN
        RAISE EXCEPTION 'p_retention must be a non-negative interval (got %)', p_retention;
    END IF;
    DELETE FROM public.settlement_index_queue
     WHERE processed_at IS NOT NULL
       AND processed_at < now() - p_retention;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION public.prune_ledger_outbox(interval) IS
    'DONE(성공 처리) 이후 p_retention 경과 ledger_outbox 행 삭제(반환=삭제 건수 bigint). FAILED 는 포렌식 보존.';
COMMENT ON FUNCTION public.prune_settlement_index_queue(interval) IS
    'processed_at(재색인 성공) 이후 p_retention 경과 settlement_index_queue 행 삭제(반환=삭제 건수 bigint). 미처리 행은 보존.';
