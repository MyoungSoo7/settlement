-- V20260818110000: 포인트 원장 GL 소비 매핑 — 계정·ref_type·owner CHECK 확장
--
-- order-service 의 포인트 원장(docs/plan/point-ledger.md)이 잔고 변화를 이벤트로 내보내면
-- account-service 가 분개로 집계한다. 미사용 포인트는 회사가 고객에게 진 <b>빚</b>이고,
-- 충전 보너스·구매 적립은 회사가 얹은 <b>판촉비</b>이며, 소멸분은 <b>이익</b>이다.
--
-- 설계 전제 하나가 이 CHECK 집합에 그대로 드러난다 — **포인트 사용의 대변은 현금(CASH)이다.**
-- settlement.created 는 주문금액만큼 현금이 들어왔다고 전기하는데, 포인트로 결제된 몫은 그 시점에
-- 현금이 들어오지 않았다(충전 시점에 이미 받았다). POINT_USED 전표가 그 가공의 현금 유입을 상계해
-- settlement 의 기존 분개를 한 줄도 고치지 않고 정합을 맞춘다.
--
-- 확장 4종 (값 집합의 정본은 AccountEntry 팩토리 — SchemaEnumContractIT 가 CHECK↔팩토리 일치 검증):
--   A) debit/credit 계정 CHECK: POINT_LIABILITY / POINT_PROMOTION_EXPENSE / POINT_BREAKAGE_INCOME
--   B) ref_type CHECK: 포인트 5종
--   C) owner_type CHECK(account_entries·account_balances): CUSTOMER
--      — 포인트 보유자는 셀러도 차주도 예금가입자도 아닌 구매 회원이다
--   D) owner_id 형식 CHECK: CUSTOMER = userId 숫자 문자열('^[0-9]+$')

-- ── A) GL 계정 열거 CHECK 재작성 — 포인트 3계정 ───────────────────────────────────────────
-- 차변·대변 CHECK 는 같은 값 집합을 쓴다(선례 유지). 어떤 계정이 어느 변에 오는지는 GlAccount 의
-- AccountSide 와 AccountEntry 팩토리가 강제하며, SQL 에 방향을 복제하면 조용한 드리프트가 생긴다.
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'SECURED_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                 'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                 'WITHHOLDING_PAYABLE',
                                 'TIME_DEPOSIT_LIABILITY','INSTALLMENT_SAVINGS_LIABILITY',
                                 'RETIREMENT_PENSION_LIABILITY','INTEREST_EXPENSE',
                                 'POINT_LIABILITY','POINT_PROMOTION_EXPENSE',
                                 'POINT_BREAKAGE_INCOME')) NOT VALID;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'SECURED_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                  'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED',
                                  'WITHHOLDING_PAYABLE',
                                  'TIME_DEPOSIT_LIABILITY','INSTALLMENT_SAVINGS_LIABILITY',
                                  'RETIREMENT_PENSION_LIABILITY','INTEREST_EXPENSE',
                                  'POINT_LIABILITY','POINT_PROMOTION_EXPENSE',
                                  'POINT_BREAKAGE_INCOME')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ── B) ref_type CHECK 확장 — 포인트 5종 ────────────────────────────────────────────────────
--   충전 원금 / 적립·보너스 / 사용 / 환불 복원 / 소멸
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET','WITHHOLDING_ACCRUED',
                            'PAYOUT_ADVANCE','SECURED_LOAN_DISBURSED','SECURED_LOAN_REPAID',
                            'SECURED_LOAN_PRINCIPAL_REPAID',
                            'TIME_DEPOSIT_OPENED','TIME_DEPOSIT_INTEREST','TIME_DEPOSIT_CLOSED',
                            'SAVINGS_INSTALLMENT_PAID','SAVINGS_INTEREST','SAVINGS_CLOSED',
                            'PENSION_CONTRIBUTION_PAID','PENSION_INTEREST','PENSION_BENEFIT_PAID',
                            'PENSION_MID_WITHDRAWN',
                            'POINT_CHARGED','POINT_GRANTED','POINT_USED','POINT_RESTORED',
                            'POINT_EXPIRED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리가 정본(ADR 0026 Option ① + ADR 0029 §B + 담보대출 + 수신 3종 + 포인트 원장). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';

-- ── C) owner_type CHECK 확장 — CUSTOMER (양 테이블 동일 도메인) ─────────────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_owner_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER', 'DEPOSITOR', 'CUSTOMER')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_owner_type;

ALTER TABLE account_balances DROP CONSTRAINT chk_account_balance_owner_type;
ALTER TABLE account_balances
    ADD CONSTRAINT chk_account_balance_owner_type
        CHECK (owner_type IN ('SELLER', 'CORPORATE', 'BORROWER', 'DEPOSITOR', 'CUSTOMER')) NOT VALID;
ALTER TABLE account_balances VALIDATE CONSTRAINT chk_account_balance_owner_type;

-- ── D) owner_id 다형 자연키 형식 CHECK — CUSTOMER = userId 숫자 문자열 ─────────────────────
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_owner_id_format;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_owner_id_format
        CHECK (
            (owner_type = 'SELLER'    AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'CORPORATE' AND owner_id ~ '^[0-9A-Z]{6}$')
         OR (owner_type = 'BORROWER'  AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'DEPOSITOR' AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'CUSTOMER'  AND owner_id ~ '^[0-9]+$')
        ) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_owner_id_format;

COMMENT ON COLUMN account_entries.owner_id IS
    '다형 자연키 — SELLER=sellerId(숫자), CORPORATE=stockCode(6자리), BORROWER=borrowerUserId(숫자), DEPOSITOR=userId(숫자), CUSTOMER=userId(숫자). 형식은 chk_account_entry_owner_id_format 이 강제.';

-- ── ROLLBACK NOTES ────────────────────────────────────────────────────────────
-- 직전 마이그레이션(V20260809030500)의 CHECK 정의를 그대로 재적용하면 되돌아간다.
-- 단 포인트 분개가 이미 적재된 뒤라면 VALIDATE 가 실패하므로, 되돌리기 전에 해당 행을
-- 역분개로 정리해야 한다(POSTED 전표는 수정하지 않는다).
