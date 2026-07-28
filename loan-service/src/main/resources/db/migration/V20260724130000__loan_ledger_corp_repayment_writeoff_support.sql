-- V20260724130000: 기업대출 상환·대손 상각 전표 DB 지원 — 코드리뷰 후속(원장 계약 갭 봉합)
--
-- 이번 릴리스에서 LoanLedgerEntry 팩토리가 2종 늘었다:
--   corporateRepayment → ref_type='CORP_REPAYMENT' (기업대출 상환, ref_id=loanId)
--   badDebtWriteOff    → ref_type='BAD_DEBT'       (대손 상각,   ref_id=loanId, 대출 1건당 1회)
-- 그러나 V20260717200000 의 두 제약이 이를 반영하지 못해 상환·상각 경로가 실 DB 에서 전부 실패한다:
--
--   [갭 1] chk_loan_ledger_ref_type 이 5종만 허용 → CORP_REPAYMENT·BAD_DEBT INSERT 가
--          check_violation(23514)로 롤백된다. 상환(POST /loans/corporate/{id}/repay)·상각
--          (POST /loans/{id}/write-off, LoanOverdueScheduler 자동 상각)이 첫 실행부터 500.
--   [갭 2] uq_loan_ledger_reference_accounts(ref_type,ref_id,debit,credit) 는 "(ref_type,ref_id)
--          1회 기표" 설계 전제인데(V20260717200000 [지적 2]), CORP_REPAYMENT 은 ref_id=loanId 로
--          부분상환 N회가 정상이다. 2회차 상환이 동일 4-튜플이라 중복 기표로 오판돼 막힌다.
--
-- ── ① ref_type CHECK 를 팩토리 실값 7종으로 확장 ──────────────────────────────
-- 기존 제약을 교체한다. 신규 2종은 지금까지 INSERT 자체가 실패해 온 값이라 기존 행에 존재하지 않으므로
-- VALIDATE 가 즉시 통과한다(재검증 안전). 팩토리↔CHECK 정합은 SchemaEnumContractIT 가 빌드 시점에 대조한다.
ALTER TABLE loan_ledger_entries DROP CONSTRAINT IF EXISTS chk_loan_ledger_ref_type;
ALTER TABLE loan_ledger_entries
    ADD CONSTRAINT chk_loan_ledger_ref_type
        CHECK (ref_type IN ('DISBURSE','FEE','REPAYMENT','CORP_DISBURSE','CORP_FEE',
                            'CORP_REPAYMENT','BAD_DEBT')) NOT VALID;
ALTER TABLE loan_ledger_entries VALIDATE CONSTRAINT chk_loan_ledger_ref_type;

-- ── ② 중복 분개 유니크를 CORP_REPAYMENT 제외 부분 인덱스로 재정의 ──────────────
-- CORP_REPAYMENT(기업대출 부분상환)은 대출 1건당 N회 정상 발생이라 유니크 대상에서 제외한다.
-- 나머지 6종은 여전히 (ref_type,ref_id) 1회 기표라 재시도·동시성 이중 기표 방어가 유지된다
-- (BAD_DEBT 는 상각 1회 → 유니크에 남겨 재상각·동시 상각을 계속 차단).
-- ※ CORP_REPAYMENT 의 애플리케이션 레벨 재요청(중복 제출) 멱등은 별도 과제다 — 동시성은
--   RepayCorporateLoanService 의 findByIdForUpdate 비관적 락이 직렬화한다.
DROP INDEX IF EXISTS uq_loan_ledger_reference_accounts;
CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_ledger_reference_accounts
    ON loan_ledger_entries (ref_type, ref_id, debit, credit)
    WHERE ref_type <> 'CORP_REPAYMENT';

COMMENT ON INDEX uq_loan_ledger_reference_accounts IS
    '동일 참조(ref_type,ref_id)·계정쌍 이중 기표 차단. 단 CORP_REPAYMENT(기업대출 부분상환)은 '
    '대출 1건당 N회 정상 발생이라 제외 — 재요청 멱등은 애플리케이션 레벨(비관락+후속 멱등키)에서 처리.';
