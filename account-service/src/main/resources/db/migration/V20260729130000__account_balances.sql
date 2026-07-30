-- ADR 0030 Phase 1 — 통제계정 잔액 실체화 (account_balances)
--
-- 왜: 지금까지 "통제계정 잔액"은 어디에도 실체가 없고 필요할 때마다 원장을 즉석 재합산했다
--   (AccountEntryRepository.netBalanceByOwnerAndAccount). 그 비용은 O(셀러 전표 수)이고,
--   payout 은 advisory 락을 쥔 채 이 재합산을 돌아 락 보유시간이 이력 길이에 비례해 늘어난다
--   (ADR 0030 §결함 2). 잔액을 실체화하면 조회가 O(1) 이 되고, 잔액 행의 행 잠금이 이후
--   Phase 2(전 debit 레그 잔액 인식 라우팅)의 직렬화 기반이 된다.
--
-- 부호 규약: balance = Σcredit − Σdebit (**credit-positive**).
--   기존 netBalanceByOwnerAndAccount 와 정확히 같은 식이라 SELLER_PAYABLE 조회 의미가 그대로 보존된다.
--   계정의 정상잔액 방향(DEBIT/CREDIT)은 SQL 에 복제하지 않는다 — GlAccount enum 이 단일 출처이고,
--   여기에 방향을 하드코딩하면 계정 추가 시 조용한 드리프트가 생긴다(해석은 도메인 몫).
--
-- 정본은 여전히 원장(account_entries)이다. 이 테이블은 파생 캐시이며 Phase 3 의 주기 대사로 증명한다.

CREATE TABLE IF NOT EXISTS account_balances (
    id          BIGSERIAL      PRIMARY KEY,
    owner_type  VARCHAR(20)    NOT NULL,   -- SELLER / CORPORATE (account_entries 와 동일 도메인)
    owner_id    VARCHAR(64)    NOT NULL,   -- sellerId 문자열 또는 stockCode
    account     VARCHAR(40)    NOT NULL,   -- GlAccount
    balance     NUMERIC(19, 2) NOT NULL,   -- Σcredit − Σdebit (음수 가능 — 방지는 Phase 2 라우팅 몫)
    updated_at  TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_account_balance_owner_type CHECK (owner_type IN ('SELLER', 'CORPORATE')),
    -- UPSERT 충돌 대상 = (owner, account) 유일성
    CONSTRAINT uq_account_balance_key UNIQUE (owner_type, owner_id, account)
);

-- 백필 — 기존 전표에서 (owner, account)별 순잔액을 재합산해 초기값을 심는다(이 마이그레이션 단일 tx).
-- 전표 한 행은 차변 1 · 대변 1 이므로 두 레그로 펼쳐(UNION ALL) 계정별로 접는다.
INSERT INTO account_balances (owner_type, owner_id, account, balance)
SELECT owner_type, owner_id, account, SUM(delta)
  FROM (
        SELECT owner_type, owner_id, credit_account AS account,  amount AS delta FROM account_entries
        UNION ALL
        SELECT owner_type, owner_id, debit_account  AS account, -amount AS delta FROM account_entries
       ) legs
 GROUP BY owner_type, owner_id, account
ON CONFLICT (owner_type, owner_id, account) DO NOTHING;
