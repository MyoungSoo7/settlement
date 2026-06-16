-- V3: 로컬 정산 뷰 (DB-per-service 의 핵심)
--
-- loan-service 는 settlements 테이블을 직접 읽을 수 없으므로(자체 DB),
-- 한도 산정에 필요한 "셀러별 미지급 정산예정금" 을 settlement 의 SettlementCreated 이벤트로
-- 받아 자체 DB 에 materialize 한다. settlementId 를 PK 로 두어 이벤트 재수신 시 멱등 UPSERT.
--
--   status: PENDING(정산 생성, 미지급=담보) → CONFIRMED(정산 확정, 상환 차감 시점)
--   1차는 PAID 상태를 두지 않는다 (loan 은 payout 완료를 알 수 없음 — 상환은 CONFIRMED 시점 확정).

CREATE TABLE IF NOT EXISTS seller_settlement_view (
    settlement_id  BIGINT         PRIMARY KEY,         -- settlement 측 정산 ID (이벤트로 수신)
    seller_id      BIGINT         NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    due_date       DATE,                               -- 정산예정일 (수수료 일수 산정용)
    status         VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_ssv_status CHECK (status IN ('PENDING', 'CONFIRMED'))
);

-- 셀러별 미지급(PENDING) 정산예정금 합계 조회 — 한도 산정의 핫패스
CREATE INDEX IF NOT EXISTS idx_ssv_seller_status
    ON seller_settlement_view (seller_id, status);
