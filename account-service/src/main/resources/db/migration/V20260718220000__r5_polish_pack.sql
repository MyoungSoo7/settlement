-- V20260718220000: R5 리뷰 후속 폴리시 팩 (account/opslab)
--
-- [① BRIN 튜닝] brin_account_entries_occurred_at 을 pages_per_range=32, autosummarize=on 으로 재생성
--   (order/settlement ledger BRIN 과 동형 — 최근-구간 시산표 조회 정밀도 + 신규 블록 자동 요약).
-- [② DEFAULT 파티션 프로브] default_partition_rows() — DEFAULT 파티션 유입 행 수 반환(0 정상,
--   >0 = 런웨이 소진 신호). 모니터링 폴링 대상(ADR 0027).

-- ① BRIN 튜닝 재생성
DROP INDEX IF EXISTS brin_account_entries_occurred_at;
CREATE INDEX brin_account_entries_occurred_at
    ON account_entries USING BRIN (occurred_at)
    WITH (pages_per_range = 32, autosummarize = on);

COMMENT ON INDEX brin_account_entries_occurred_at IS
    'append-only 시간순 적재 GL 의 기간 집계(시산표) 범위 스캔용 BRIN — pages_per_range=32(정밀도), autosummarize=on(신규 블록 자동 요약).';

-- ② DEFAULT 파티션 프로브
CREATE OR REPLACE FUNCTION default_partition_rows()
RETURNS TABLE(parent_table text, default_rows bigint)
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
AS $$
DECLARE
    r record;
    cnt bigint;
BEGIN
    FOR r IN
        SELECT p.relname AS parent, c.relname AS child
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'opslab'
          AND c.relname LIKE '%\_default' ESCAPE '\'
    LOOP
        EXECUTE format('SELECT count(*) FROM %I', r.child) INTO cnt;
        parent_table := r.parent;
        default_rows := cnt;
        RETURN NEXT;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION default_partition_rows() IS
    'DEFAULT 파티션 유입 행 수 프로브 — 0 정상, >0 은 런웨이 소진 신호(ensure_* 미발화). 모니터링 폴링 대상(ADR 0027).';
