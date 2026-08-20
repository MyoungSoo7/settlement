-- V20260818140000: 기프트카드 GL 소비 매핑 — 계정·ref_type CHECK 확장
--
-- order-service 의 기프트카드 원장(docs/plan/gift-card-ledger.md)이 잔액 변화를 이벤트로 내보내면
-- account-service 가 분개로 집계한다.
--
-- 포인트 계정을 재사용하지 않는 이유가 이 마이그레이션의 요지다: 상품권 부채와 포인트 부채를 한
-- 계정에 담으면 화면에서도 시산표에서도 둘을 나눌 수 없다. 이름이 POINT_ 접두라 의미도 어긋난다.
--
-- owner_type 은 CUSTOMER 를 그대로 쓴다(포인트에서 이미 추가). 상품권 보유자도 구매 회원이다.
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
                                 'POINT_BREAKAGE_INCOME',
                                 'GIFT_CARD_LIABILITY','GIFT_CARD_PROMOTION_EXPENSE',
                                 'GIFT_CARD_BREAKAGE_INCOME')) NOT VALID;
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
                                  'POINT_BREAKAGE_INCOME',
                                  'GIFT_CARD_LIABILITY','GIFT_CARD_PROMOTION_EXPENSE',
                                  'GIFT_CARD_BREAKAGE_INCOME')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ref_type 확장 — 기프트카드 4종.
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
                            'POINT_EXPIRED','POINT_REVOKED',
                            'GIFTCARD_REGISTERED','GIFTCARD_USED','GIFTCARD_RESTORED',
                            'GIFTCARD_EXPIRED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리가 정본(ADR 0026 Option ① + ADR 0029 §B + 담보대출 + 수신 3종 + 포인트 원장 + 기프트카드 원장). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';

-- ── ROLLBACK NOTES ────────────────────────────────────────────────────────────
-- 직전 마이그레이션(V20260818120000)의 CHECK 를 재적용한다. 기프트카드 분개가 이미 있으면
-- VALIDATE 가 실패하므로 먼저 역분개로 정리해야 한다(POSTED 전표는 수정하지 않는다).
