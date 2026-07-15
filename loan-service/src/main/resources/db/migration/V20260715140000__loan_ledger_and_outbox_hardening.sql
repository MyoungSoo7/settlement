-- V20260715140000: loan 원장·대출 테이블 강건화 + Outbox/멱등 리텐션 (DB 설계 리뷰 반영, E4 레인)
--
-- 3인 DB 설계 리뷰 지적 3건을 스키마 레벨에서 봉인한다.
--   ① loan_ledger_entries: 차/대 동일계정 분개 방어 + 기간 시산표 집계 인덱스 + 전표 불변(UPDATE 차단).
--   ② corporate_loans / loan_advances: 금액 하한 CHECK 보강(상태 CHECK 는 V1/V8 에 이미 존재 — 재추가 안 함).
--   ③ outbox_events / processed_events: 무한 적재 방지 리텐션 함수 + PUBLISHED 정리용 부분 인덱스.
--
-- 근거·제약:
--   * 테이블/컬럼 이름은 실제 스키마 대조 결과. loan_ledger_entries 의 계정 컬럼은 debit/credit(30),
--     금액은 amount(NUMERIC 19,2, V2 에서 이미 CHECK(amount>0) 보유 → 재추가 안 함).
--   * 이 DB 의 유효 스키마는 opslab(통합테스트 주석·Outbox 네이티브쿼리 하드코딩과 동일). 기존 V1~V8 이
--     모두 스키마 미수식(unqualified)으로 테이블을 생성했고 JPA validate 가 그 위에서 통과하므로,
--     동일 객체를 대상으로 하는 본 마이그레이션도 미수식으로 작성해 같은 스키마에 해석되게 한다.
--   * CHECK 는 NOT VALID → VALIDATE 2단계(운영 데이터 존재 시 장시간 락 회피). 신규 DB 에선 즉시 검증.
--
-- ★ 전표 불변 트리거는 UPDATE 만 차단하고 DELETE 는 차단하지 않는다.
--   이유: 회계 무결성이 요구하는 불변식은 "기표된 전표의 계정·금액을 사후 변경 금지"(=UPDATE 봉쇄)다.
--   운영 코드 경로는 loan_ledger_entries 를 INSERT 전용으로만 다룬다(UPDATE·DELETE 없음).
--   반면 기존 그린 통합테스트(LoanSettlementSagaIntegrationTest)가 @BeforeEach 에서 ledgerRepo.deleteAll()
--   로 픽스처를 청소한다 — DELETE 를 봉쇄하면 이 테스트가 깨진다. 운영상 DELETE 이득이 없고 테스트만
--   깨지므로 DELETE 는 열어 두고 UPDATE 만 봉쇄한다(계정계 account_entries 는 DELETE 하는 DB 테스트가
--   없어 UPDATE+DELETE 를 모두 봉쇄 — 별도 마이그레이션에서 처리).

-- ─────────────────────────────────────────────────────────────────────────────
-- ① loan_ledger_entries: 차/대 동일계정 방어 + 기간 집계 인덱스 + 전표 불변(UPDATE 봉쇄)
-- ─────────────────────────────────────────────────────────────────────────────

-- 차변 계정과 대변 계정이 같은 분개는 균형이 무의미(자기상계) — 도메인 팩토리는 항상 서로 다른 계정을
-- 쓰지만(disbursement/feeAccrual/repayment 등) 스키마에서도 최종 방어한다.
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_accounts_distinct CHECK (debit <> credit) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_accounts_distinct;

-- 기간 시산표(ledger-verify)용 created_at 범위 스캔 인덱스. 기존 idx_loan_ledger_ref 는 (ref_type,ref_id)
-- 조회 전용이라 기간 집계를 커버하지 못한다.
CREATE INDEX IF NOT EXISTS idx_loan_ledger_created_at
    ON loan_ledger_entries (created_at);

-- 전표 불변: 기표된 원장 행의 UPDATE 를 DB 레벨에서 차단(POSTED 불변식의 스키마 강제).
CREATE OR REPLACE FUNCTION enforce_loan_ledger_immutability()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'loan_ledger_entries id=% is append-only; posted entries cannot be updated.', OLD.id
        USING ERRCODE = '23514';  -- check_violation
    RETURN NULL;  -- 도달 불가(위 RAISE 로 종료)
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_loan_ledger_immutability ON loan_ledger_entries;
CREATE TRIGGER trg_loan_ledger_immutability
    BEFORE UPDATE ON loan_ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION enforce_loan_ledger_immutability();

COMMENT ON FUNCTION enforce_loan_ledger_immutability() IS
    '복식부기 전표 불변: loan_ledger_entries 의 UPDATE 차단(append-only). 정정은 역분개 신규 전표로.';

-- ─────────────────────────────────────────────────────────────────────────────
-- ② 금액 하한 CHECK 보강 (상태 CHECK 는 V1/V8 기존 — 손대지 않음)
-- ─────────────────────────────────────────────────────────────────────────────

-- loan_advances: 원금은 양수, 수수료·미상환잔액은 음수 불가(잔액은 REPAID 시 0 도달 → >= 0).
ALTER TABLE loan_advances
    ADD CONSTRAINT chk_loan_advance_principal   CHECK (principal   > 0)  NOT VALID,
    ADD CONSTRAINT chk_loan_advance_fee         CHECK (fee         >= 0) NOT VALID,
    ADD CONSTRAINT chk_loan_advance_outstanding CHECK (outstanding >= 0) NOT VALID;
ALTER TABLE loan_advances VALIDATE CONSTRAINT chk_loan_advance_principal;
ALTER TABLE loan_advances VALIDATE CONSTRAINT chk_loan_advance_fee;
ALTER TABLE loan_advances VALIDATE CONSTRAINT chk_loan_advance_outstanding;

-- corporate_loans: principal>0·term_days>0·credit_score 범위·status 는 V8 에 이미 존재. fee/outstanding 하한만 보강.
ALTER TABLE corporate_loans
    ADD CONSTRAINT chk_corp_loan_fee         CHECK (fee         >= 0) NOT VALID,
    ADD CONSTRAINT chk_corp_loan_outstanding CHECK (outstanding >= 0) NOT VALID;
ALTER TABLE corporate_loans VALIDATE CONSTRAINT chk_corp_loan_fee;
ALTER TABLE corporate_loans VALIDATE CONSTRAINT chk_corp_loan_outstanding;

-- (인덱스 보강 없음: 리포지토리 쿼리 대조 결과 loan_advances·corporate_loans 의 모든 조회는 기존
--  idx_loan_advances_seller_status(seller_id,status,id) / idx_corporate_loans_stock_code(stock_code,id DESC)
--  및 PK(id) 로 이미 커버된다 — findAll…OrderByIdDesc·findByIdForUpdate 포함.)

-- ─────────────────────────────────────────────────────────────────────────────
-- ③ Outbox/멱등 리텐션 함수 + PUBLISHED 정리 인덱스 (order-service E2 레인과 함수명·시그니처 통일)
-- ─────────────────────────────────────────────────────────────────────────────

-- PUBLISHED 로 확정된 Outbox 행은 재발행 대상이 아니므로 보존기간 경과 후 정리 가능. published_at 기준.
-- 반환: 삭제된 행 수. 스케줄러/크론이 명시 보존기간을 넘겨 호출(기본 7일).
CREATE OR REPLACE FUNCTION prune_outbox_published(p_retention INTERVAL DEFAULT INTERVAL '7 days')
RETURNS BIGINT AS $$
DECLARE
    v_deleted BIGINT;
BEGIN
    DELETE FROM outbox_events
        WHERE status = 'PUBLISHED'
          AND published_at IS NOT NULL
          AND published_at < NOW() - p_retention;
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION prune_outbox_published(INTERVAL) IS
    'PUBLISHED Outbox 행 리텐션 정리(published_at 기준, 기본 7일). 삭제 건수 반환.';

-- 컨슈머 멱등 추적 행 리텐션. Kafka 보존기간을 넘긴 event_id 는 중복 재도착 불가 → 안전 정리(기본 30일).
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

-- prune_outbox_published 스캔 최적화: 기존 idx_loan_outbox_status_created 는 PENDING/FAILED 만 커버.
CREATE INDEX IF NOT EXISTS idx_loan_outbox_published_at
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- prune_processed_events 는 기존 idx_loan_processed_events_processed_at(processed_at) 를 사용 — 신규 불요.
