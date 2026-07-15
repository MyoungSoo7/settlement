-- V20260717200000: loan 원장·대출 이력 대칭화 — DB 설계 리뷰 R3 후속 (G2)
--
-- [지적 1 · A-med] enforce_loan_ledger_immutability(V20260715140000)가 UPDATE 만 차단하고 DELETE 를
--   열어뒀다(당시 근거: 통합테스트 deleteAll 픽스처). 운영 GL 행의 물리 삭제가 가능하다는 뜻이므로
--   DELETE 까지 차단으로 확장한다. 테스트 픽스처는 TRUNCATE 로 전환했다 — PostgreSQL 의 row-level
--   BEFORE 트리거는 TRUNCATE 에 발화하지 않으므로(문서화된 동작) 테스트 격리와 운영 불변이 공존한다.
-- [지적 2 · A-med] settlement ledger 의 중복 분개 방지 유니크(uq_ledger_reference_accounts)에 대응하는
--   방어가 loan 원장에 없다. 기표 경로 전수 확인 결과 (ref_type, ref_id) 조합은 설계상 1회 기표다:
--     DISBURSE/FEE/CORP_DISBURSE/CORP_FEE → ref_id=loanId (대출 1건당 1회),
--     REPAYMENT → ref_id=settlementId (loan_repayments UNIQUE(settlement_id) 로 정산 1건당 상환 1회).
--   따라서 (ref_type, ref_id, debit, credit) 유니크는 재시도·동시성 이중 기표만 차단하고 정상 경로와
--   충돌하지 않는다.
-- [지적 3 · B-low] ref_type 값 집합이 주석으로만 존재 — 코드 실값(LoanLedgerEntry 팩토리 5종)으로 CHECK.
--   V2 주석의 3종(DISBURSE/FEE/REPAYMENT)은 기업대출 추가(V8) 이후 5종이 정본이다.
-- [지적 4 · B-med] corporate_loans 는 4상태 머신(REQUESTED/APPROVED/DISBURSED/REPAID/REJECTED)인데
--   전이 시각 추적 컬럼이 없다 — investment_orders(V20260715142000)와 동형으로 updated_at 을 신설한다.
--   credit_grade(A~E 스냅샷)도 값 CHECK 가 없어 보강한다.

-- ── ① 원장 불변 트리거를 UPDATE OR DELETE 로 확장 ─────────────────────────────
CREATE OR REPLACE FUNCTION enforce_loan_ledger_immutability()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'loan_ledger_entries id=% is append-only; % is not allowed (정정은 역분개 신규 전표로).',
        OLD.id, TG_OP
        USING ERRCODE = '23514';  -- check_violation
    RETURN NULL;  -- 도달 불가(위 RAISE 로 종료)
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_loan_ledger_immutability ON loan_ledger_entries;
CREATE TRIGGER trg_loan_ledger_immutability
    BEFORE UPDATE OR DELETE ON loan_ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION enforce_loan_ledger_immutability();

COMMENT ON FUNCTION enforce_loan_ledger_immutability() IS
    '복식부기 전표 불변: loan_ledger_entries 의 UPDATE·DELETE 차단(append-only). 정정은 역분개 신규 전표로. '
    'row 트리거는 TRUNCATE 에 발화하지 않음 — 테스트 컨테이너 격리 초기화는 TRUNCATE 사용.';

-- ── ② 중복 분개 방지 유니크 (settlement uq_ledger_reference_accounts 동형) ────
-- 유니크 인덱스는 NOT VALID 불가 → 생성 시 전수 검사. 기존 DB 에 중복이 있으면 생성이 실패해
-- 그 자체가 이중 기표 결함 신호가 된다(의도된 fail-loud).
CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_ledger_reference_accounts
    ON loan_ledger_entries (ref_type, ref_id, debit, credit);

COMMENT ON INDEX uq_loan_ledger_reference_accounts IS
    '동일 참조(ref_type, ref_id)·계정쌍의 이중 기표 차단 — 컨슈머 재시도/동시성 TOCTOU 방어의 DB 최종선.';

-- ── ③ ref_type 값 CHECK (코드 실값 5종 — LoanLedgerEntry 팩토리가 정본) ───────
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_ref_type
        CHECK (ref_type IN ('DISBURSE','FEE','REPAYMENT','CORP_DISBURSE','CORP_FEE')) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_ref_type;

-- ── ④ corporate_loans: updated_at 신설(3단계) + touch 트리거 + credit_grade CHECK ──
-- JPA 엔티티는 updated_at 을 매핑하지 않으므로(ddl-auto=validate 는 미매핑 추가 컬럼 무시)
-- BEFORE UPDATE 트리거가 DB 레벨에서 전이 시각을 유지한다 — investment_orders 와 동일 패턴.
ALTER TABLE corporate_loans
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE corporate_loans SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE corporate_loans ALTER COLUMN updated_at SET DEFAULT NOW();

CREATE OR REPLACE FUNCTION touch_corporate_loan_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_corporate_loan_updated_at ON corporate_loans;
CREATE TRIGGER trg_corporate_loan_updated_at
    BEFORE UPDATE ON corporate_loans
    FOR EACH ROW
    EXECUTE FUNCTION touch_corporate_loan_updated_at();

COMMENT ON FUNCTION touch_corporate_loan_updated_at() IS
    'corporate_loans 상태 전이(UPDATE)마다 updated_at 을 NOW() 로 DB 레벨 갱신(엔티티 미매핑 컬럼).';

ALTER TABLE corporate_loans
    ADD CONSTRAINT chk_corp_loan_credit_grade
        CHECK (credit_grade IN ('A','B','C','D','E')) NOT VALID;
ALTER TABLE corporate_loans VALIDATE CONSTRAINT chk_corp_loan_credit_grade;
