-- V20260715141000: 계정계 GL 분개 append-only 강건화 + 멱등 리텐션 (DB 설계 리뷰 반영, E4 레인)
--
-- 3인 DB 설계 리뷰 지적: account_entries 는 차/대 동일계정 방어와 append-only 강제가 없고,
-- processed_events 는 무한 적재된다. 계정계는 loan·investment·settlement 이벤트를 소비만 하는
-- 원천 GL 원장이므로(발행 없음), 한 번 기표된 분개는 수정·삭제가 원천 차단돼야 한다.
--
-- 근거·제약:
--   * 컬럼 이름은 실제 스키마 대조: 계정 컬럼 debit_account/credit_account(40), 금액 amount(NUMERIC 19,2,
--     V1 에 이미 CHECK(amount>0)=chk_account_entry_amount 보유 → 재추가 안 함), 기표시각 occurred_at.
--   * 기존 V1~V3 이 스키마 미수식으로 생성했고 JPA validate 가 그 위에서 통과하므로 본 마이그레이션도
--     미수식으로 작성(동일 스키마 해석).
--   * CHECK 는 NOT VALID → VALIDATE 2단계.
--   * append-only 트리거는 UPDATE·DELETE 를 모두 봉쇄한다. 운영 코드(AccountEntryPersistenceAdapter)는
--     INSERT(멱등 선점 후 save)·SELECT 만 수행하고, DB 를 실제로 부팅하는 테스트는 순수 Mockito 단위테스트
--     뿐이라(deleteAll 하는 통합테스트 없음) 전면 봉쇄가 그린 테스트를 깨지 않는다.

-- 차변/대변이 같은 계정인 분개 방어(자기상계 무의미). 소비 매핑은 항상 서로 다른 계정을 쓴다.
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_accounts_distinct CHECK (debit_account <> credit_account) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_accounts_distinct;

-- 기간 시산표(trial-balance-verify)용 occurred_at 범위 스캔 인덱스. 현행 trialBalance() 는 findAll() 이지만,
-- 기간 한정 시산표·GL 결산 조회가 계정계의 본질 기능이므로 결산 시각 기준 범위 스캔을 위한 인덱스를 마련한다.
CREATE INDEX IF NOT EXISTS idx_account_entries_occurred_at
    ON account_entries (occurred_at);

-- append-only: 기표된 GL 분개의 UPDATE·DELETE 를 DB 레벨에서 차단(소비 전용 원장의 원천 불변).
CREATE OR REPLACE FUNCTION enforce_account_entry_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'account_entries is append-only; GL entries cannot be modified or deleted (op=%).', TG_OP
        USING ERRCODE = '23514';  -- check_violation
    RETURN NULL;  -- 도달 불가(위 RAISE 로 종료)
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_account_entry_append_only ON account_entries;
CREATE TRIGGER trg_account_entry_append_only
    BEFORE UPDATE OR DELETE ON account_entries
    FOR EACH ROW
    EXECUTE FUNCTION enforce_account_entry_append_only();

COMMENT ON FUNCTION enforce_account_entry_append_only() IS
    '계정계 GL 분개 append-only: account_entries 의 UPDATE·DELETE 차단. 정정은 역분개 신규 분개로.';

-- 컨슈머 멱등 추적 행 리텐션(account 는 소비 전용 → outbox 없음, processed_events 만 정리).
-- Kafka 보존기간을 넘긴 event_id 는 중복 재도착 불가 → 안전 정리(기본 30일). 반환: 삭제 건수.
CREATE OR REPLACE FUNCTION prune_processed_events(p_retention INTERVAL DEFAULT INTERVAL '30 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    DELETE FROM processed_events
        WHERE processed_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_processed_events(INTERVAL) IS
    'processed_events 멱등 추적 행 리텐션 정리(processed_at 기준, 기본 30일). 삭제 건수 반환.';

-- prune_processed_events 는 기존 idx_account_processed_events_processed_at(processed_at) 를 사용 — 신규 불요.
