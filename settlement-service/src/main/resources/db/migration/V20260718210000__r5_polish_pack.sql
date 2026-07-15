-- V20260718210000: R5 리뷰 후속 폴리시 팩 (settlement_db)
--
-- [① 다형참조 트레이드오프] ledger_entries.reference_id 는 reference_type(SETTLEMENT|REFUND) 분기
--   다형참조 — REFUND 는 cross-DB(order 소유)라 FK 자체가 불가하고, SETTLEMENT 도 대칭성을 위해
--   FK 를 두지 않는다(중복분개 방지=uq_ledger_reference_accounts, 참조 정합=일일 대사). 각인.
-- [② BRIN 튜닝] idx_ledger_entries_created_brin 을 pages_per_range=32, autosummarize=on 으로 재생성
--   (order V20260718110000 과 동형 — 최근-구간 조회 정밀도 + 신규 블록 자동 요약).
-- [③ DEFAULT 파티션 프로브] default_partition_rows() — DEFAULT 파티션 유입 행 수 반환(0 정상,
--   >0 = 런웨이 소진 신호). 모니터링 폴링 대상(ADR 0027 결과 절 감시 요구 구현).

-- ① 다형참조 트레이드오프 각인
COMMENT ON COLUMN public.ledger_entries.reference_id IS
    'reference_type(SETTLEMENT|REFUND) 분기 다형참조 — FK 불가(의도, REFUND 는 cross-DB). 중복분개 방지는 uq_ledger_reference_accounts, 참조 정합은 일일 대사가 담당.';

-- ② BRIN 튜닝 재생성
DROP INDEX IF EXISTS public.idx_ledger_entries_created_brin;
CREATE INDEX idx_ledger_entries_created_brin
    ON public.ledger_entries USING BRIN (created_at)
    WITH (pages_per_range = 32, autosummarize = on);

-- ③ DEFAULT 파티션 프로브
CREATE OR REPLACE FUNCTION default_partition_rows()
RETURNS TABLE(parent_table text, default_rows bigint)
LANGUAGE plpgsql
SET search_path = public, pg_catalog
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
        WHERE n.nspname = 'public'
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
