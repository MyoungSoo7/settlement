-- V20260724110000: 실지급 초과분 회수채권 채널(PAYOUT_ADVANCE) ref_type 확장
--
-- 독립 GL 감사 MED-3 봉합: 대응하는 SELLER_PAYABLE 크레딧이 없는 실지급(예: 수동 송금)은
-- payoutCompleted(DR SELLER_PAYABLE / CR CASH) 만으로는 SELLER_PAYABLE 을 음수로 몰아 "완전정산
-- 통제계정 0" 불변식을 깬다. RecordPayoutService 가 payout 차변을 현재 SELLER_PAYABLE 잔액 기준으로
-- 분할해, 잔액 이내분은 PAYOUT_COMPLETED(기존), 초과분은 PAYOUT_ADVANCE(신규, DR
-- SELLER_RECOVERY_RECEIVABLE / CR CASH)로 라우팅한다. 두 전표는 sourceTopic·refId(=payoutId) 는 같고
-- refType 으로 자연키가 갈려 각각 멱등이다.
--
-- 계정 CHECK 는 변경 없음(SELLER_RECOVERY_RECEIVABLE·CASH 이미 존재). ref_type CHECK 만 확장:
-- PAYOUT_ADVANCE 1종 추가(AccountEntry 팩토리 18종). SchemaEnumContractIT 가 CHECK↔팩토리 일치 검증.

ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET','WITHHOLDING_ACCRUED',
                            'PAYOUT_ADVANCE')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 18종이 정본(ADR 0026 Option ① + ADR 0029 §B 원천징수 + 감사 MED-3 실지급 초과분 회수채권). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';
