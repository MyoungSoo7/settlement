-- V20260818120000: 적립 회수(POINT_REVOKED) ref_type 확장
--
-- 주문 취소·환불로 적립을 되가져올 때의 분개다. 계정 자체는 이미 허용된 조합
-- (DR POINT_LIABILITY / CR POINT_PROMOTION_EXPENSE)이라 계정 CHECK 는 건드리지 않고
-- ref_type 만 넓힌다.
--
-- 소멸(POINT_EXPIRED)과 굳이 나누는 이유: 소멸은 고객이 안 써서 생긴 <b>이익</b>이고,
-- 취소는 애초에 주지 말았어야 할 적립을 되돌리는 <b>비용 환입</b>이다. 한 refType 으로 합치면
-- 손익계산서에서 둘을 분리할 수 없다.
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
                            'POINT_EXPIRED','POINT_REVOKED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

-- ── ROLLBACK NOTES ────────────────────────────────────────────────────────────
-- 직전 마이그레이션(V20260818110000)의 ref_type CHECK 를 그대로 재적용한다.
-- POINT_REVOKED 분개가 이미 있으면 VALIDATE 가 실패하므로 먼저 역분개로 정리해야 한다.
