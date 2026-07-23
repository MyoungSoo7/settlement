-- V20260723100000: ADR 0026 Option ① — 지급액 기준 인식 + 유보 별도 부채계정 + 감액 사건 GL mirror
--
-- 독립 GL 감사에서 Option A v1(단일 SELLER_PAYABLE=net)이 통제계정을 0 으로 닫지 못함을 발견:
-- CR SELLER_PAYABLE(created)=net 전액 vs DR SELLER_PAYABLE(payout)=실지급액(net−holdback−회수상계−환불소진).
-- 차액(회수상계 O·소진 holdback Hc)이 GL 이벤트 없이 영구 잔존해 허위 미지급금·현금을 계상했다.
-- Option ① 은 즉시분(SELLER_PAYABLE)과 유보분(HOLDBACK_PAYABLE)을 분리 인식하고, 회수채권
-- (SELLER_RECOVERY_RECEIVABLE)을 별도 자산으로 mirror 해 완전정산 시 통제계정을 산식적으로 0 으로 닫는다.
--
-- 이 마이그레이션은 세 부분(A 계정 CHECK · B ref_type CHECK · C 유보 분리 백필)을 한 릴리스로 원자 배선한다.

-- ── A) GL 계정 열거 CHECK 재작성 — HOLDBACK_PAYABLE·SELLER_RECOVERY_RECEIVABLE 추가 ───────────────
-- GlAccount enum 과 1:1(SchemaEnumContractIT 가 정확 일치 검증). 기존 6 → 8 계정.
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_credit_account;

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                 'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED')) NOT VALID;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','HOLDBACK_PAYABLE',
                                  'SELLER_RECOVERY_RECEIVABLE','SETTLEMENT_SCHEDULED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ── B) ref_type CHECK 확장 — 신규 8종 추가 (AccountEntry 팩토리가 정본) ─────────────────────────
-- 기존 8종(SETTLEMENT_CREATED/CONFIRMED, LOAN_DISBURSED/REPAID, CORP_LOAN_DISBURSED, INVESTMENT_EXECUTED,
-- PAYOUT_COMPLETED, SETTLEMENT_SCHED_CLEARING) 유지 + Option ① 신규 8종.
ALTER TABLE account_entries DROP CONSTRAINT chk_account_entry_ref_type;
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED',
                            'PAYOUT_COMPLETED','SETTLEMENT_SCHED_CLEARING',
                            'SETTLEMENT_HOLDBACK_RECOGNIZED','HOLDBACK_RELEASED','HOLDBACK_CONSUMED',
                            'SETTLEMENT_ADJUSTED','SETTLEMENT_CANCELED_PAYABLE','SETTLEMENT_CANCELED_HOLDBACK',
                            'RECOVERY_OPENED','RECOVERY_OFFSET')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON CONSTRAINT chk_account_entry_ref_type ON account_entries IS
    'ref_type 값 집합 — AccountEntry 팩토리 16종이 정본(ADR 0026 Option ①). SchemaEnumContractIT 가 CHECK↔팩토리 일치를 빌드 시점 검증.';

-- ── C) 유보 분리 백필 — Option A v1 로 적재된 미완결 정산의 SELLER_PAYABLE→HOLDBACK_PAYABLE 재분류 ──
-- Option A v1(uncommitted, 미배포)은 settlement.created 를 DR CASH / CR SELLER_PAYABLE = net 전액으로 적재했다.
-- Option ① 은 즉시분(I)만 SELLER_PAYABLE, 유보분(H)은 HOLDBACK_PAYABLE 이어야 하므로, 아직 지급되지 않은
-- (PAYOUT_COMPLETED refId 부재) SETTLEMENT_CREATED 건의 유보분 H 를 DR SELLER_PAYABLE / CR HOLDBACK_PAYABLE
-- 로 재분류해야 한다. refId=settlementId, 멱등(SETTLEMENT_HOLDBACK_RECOGNIZED 자연키 UNIQUE 로 재실행 안전).
--
-- 단, 유보액 H 는 settlement_db(Settlement.holdbackAmount)에만 존재하고 account_entries 에는 net 만
-- 적재됐다 — 순수 SQL 로는 H 를 도출할 수 없다. 또한 Option A v1 은 어떤 환경에도 배포되지 않아 재분류 대상
-- 행이 존재하지 않는다(fresh DB no-op). 따라서 이 백필은 "H 를 account 가 알 수 있을 때만" 동작하는 방어적
-- 가드로 둔다: settlement 로부터 H 를 실어 나르는 별도 배치(재생성 이벤트 replay)가 정본 경로이며, 본 SQL 은
-- 대상 부재 시 안전한 no-op 이다. (구체 재분류 로직은 account 가 H 를 보유하지 않는 한 SQL 로 성립하지 않음 —
-- 운영 배포 시엔 cut-over 가 Option ① 이후 이벤트에만 적용되므로 백필 대상 자체가 없다.)
DO $$
BEGIN
    -- 재분류 대상: 아직 지급되지 않았고(payout 부재) 유보 분리 전인 SETTLEMENT_CREATED 는
    -- account 단독으로는 H 를 알 수 없어 대상이 없다. 대상 유무만 확인해 로깅(파괴적 작업 없음).
    IF EXISTS (
        SELECT 1 FROM account_entries a
        WHERE a.ref_type = 'SETTLEMENT_CREATED'
          AND a.credit_account = 'SELLER_PAYABLE'
          AND NOT EXISTS (
              SELECT 1 FROM account_entries h
              WHERE h.ref_type = 'SETTLEMENT_HOLDBACK_RECOGNIZED' AND h.ref_id = a.ref_id)
    ) THEN
        RAISE NOTICE 'Option ① 백필: 유보 미분리 SETTLEMENT_CREATED 존재 — H 는 settlement replay 로만 도출 가능(account 단독 SQL no-op).';
    END IF;
END $$;
