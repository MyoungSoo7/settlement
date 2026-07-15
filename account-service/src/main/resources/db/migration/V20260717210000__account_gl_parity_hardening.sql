-- V20260717210000: 계정계(정본 GL) 제약·인덱스 대칭화 — DB 설계 리뷰 R3 후속 (G2)
--
-- [지적 1 · B-med] 파생 원장인 settlement.ledger_entries 는 계정 열거 CHECK(7계정)를 갖는데,
--   전사 정본 GL 인 account_entries 는 debit_account/credit_account 값 CHECK 가 없어 정본이
--   더 느슨했다 — 미정의 계정 분개가 물리적으로 허용되는 상태. GlAccount enum 6계정으로 강제한다.
-- [지적 2 · A-med] account_entries 는 loan·investment·settlement 전 이벤트가 집약되는 최대 볼륨
--   GL 인데 occurred_at 단일 btree 뿐 — settlement ledger 의 BRIN(V20260715130100) 선택과 비대칭.
--   append-only 시간순 적재 테이블이라 BRIN 이 기간 시산표 범위 스캔에 최소 비용으로 대응한다.
--   (파티셔닝을 하지 않는 이유: 멱등 자연키 UNIQUE(source_topic, ref_type, ref_id)가 파티션 키를
--   포함하지 않아, 파티셔닝하면 전역 유일성 = 멱등 방어선이 깨진다. ledger_entries 와 동일 결정.)
-- [지적 3 · B-low] ref_type 값 집합이 주석으로만 존재 — 소비 매핑(AccountEntry 팩토리 6종)이 정본.
--   source_topic 은 CHECK 하지 않는다: 토픽 명칭은 이벤트 계약(ADR 0024)이 소유하는 정본이라
--   DB CHECK 로 이중 관리하면 토픽 개명·추가 시 스키마 변경이 강제 동반된다(드리프트 유발) —
--   의도적으로 계약 테스트 계층에 위임한다.

-- ── ① GL 계정 열거 CHECK (GlAccount enum 6계정 1:1) ───────────────────────────
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_debit_account
        CHECK (debit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                 'INVESTMENT_ASSET','SELLER_PAYABLE','SETTLEMENT_SCHEDULED')) NOT VALID,
    ADD CONSTRAINT chk_account_entry_credit_account
        CHECK (credit_account IN ('CASH','LOAN_RECEIVABLE','CORPORATE_LOAN_RECEIVABLE',
                                  'INVESTMENT_ASSET','SELLER_PAYABLE','SETTLEMENT_SCHEDULED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_debit_account;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_credit_account;

-- ── ② 기간 시산표 범위 스캔용 BRIN (settlement ledger_entries 와 동형) ─────────
CREATE INDEX IF NOT EXISTS brin_account_entries_occurred_at
    ON account_entries USING BRIN (occurred_at);

COMMENT ON INDEX brin_account_entries_occurred_at IS
    'append-only 시간순 적재 GL 의 기간 집계(시산표) 범위 스캔용 BRIN — btree 대비 저장·유지 비용 최소.';

-- ── ③ ref_type 값 CHECK (소비 이벤트→분개 매핑 6종 — AccountEntry 팩토리가 정본) ──
ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_ref_type
        CHECK (ref_type IN ('SETTLEMENT_CREATED','SETTLEMENT_CONFIRMED','LOAN_DISBURSED',
                            'LOAN_REPAID','CORP_LOAN_DISBURSED','INVESTMENT_EXECUTED')) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_ref_type;

COMMENT ON COLUMN account_entries.source_topic IS
    '분개를 발생시킨 Kafka 토픽(멱등 자연키 구성요소). 값 집합은 이벤트 계약(ADR 0024)이 정본이라 DB CHECK 로 이중 관리하지 않는다.';
