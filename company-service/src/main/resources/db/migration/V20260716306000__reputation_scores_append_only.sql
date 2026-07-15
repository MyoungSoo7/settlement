-- V20260716306000: reputation_scores append-only DB 강제 — 크로스컷 DB 리뷰 F3 (company)
--
-- [설계 근거]
--   reputation_scores 는 V3 에서 "INSERT-only 이력 스냅샷"으로 선언됐지만(점수 산식이 바뀌어도 과거 스냅샷은
--   불변이라 "그 시점에 왜 이 등급이었나"를 재현 — 여신 연계 감사 요건), 문서 규율만 있고 DB 강제가 없었다.
--   (stock_code, snapshot_date) UNIQUE 로 같은 날 재삽입은 막히나 UPDATE·DELETE 는 열려 있어 과거 스냅샷
--   변조가 가능했다. account_entries 의 append-only 트리거(enforce_account_entry_append_only)와 동형으로
--   UPDATE·DELETE 를 DB 레벨에서 봉쇄한다. 함수명은 지침대로 reputation_scores_block_modify.
--   운영 코드(ReputationScorePersistenceAdapter)는 INSERT·SELECT 만 수행하므로 그린 테스트를 깨지 않는다.
--   정정이 필요하면 새 snapshot_date 로 재삽입한다(이력 append).

CREATE OR REPLACE FUNCTION reputation_scores_block_modify()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'reputation_scores is append-only; reputation snapshots cannot be modified or deleted (op=%).', TG_OP
        USING ERRCODE = '23514';  -- check_violation
    RETURN NULL;  -- 도달 불가(위 RAISE 로 종료)
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_reputation_scores_append_only ON reputation_scores;
CREATE TRIGGER trg_reputation_scores_append_only
    BEFORE UPDATE OR DELETE ON reputation_scores
    FOR EACH ROW
    EXECUTE FUNCTION reputation_scores_block_modify();

COMMENT ON FUNCTION reputation_scores_block_modify() IS
    '기업 평판 스냅샷 append-only: reputation_scores 의 UPDATE·DELETE 차단(과거 스냅샷 불변, 감사 재현). 정정은 새 snapshot_date 로 재삽입.';
