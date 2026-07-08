-- V1: 종목 카탈로그 + 일별 시세 (market-service 자체 DB)
--
-- stocks       : 종목 마스터. 시세 피드에서 파생 upsert (상장/상장폐지 자동 반영).
--                stock_code(6자리 단축코드)는 financial/company 와 공용 비즈니스 키.
-- stock_quotes : 일별 시세 시계열. (종목, 거래일) UNIQUE upsert 로 SEED → KRX 대체.

CREATE TABLE IF NOT EXISTS stocks (
    stock_code VARCHAR(6)   PRIMARY KEY,
    isin       VARCHAR(12),
    name       VARCHAR(100) NOT NULL,
    market     VARCHAR(10)  NOT NULL,            -- KOSPI / KOSDAQ / KONEX (금융위 mrktCtg 와 1:1)
    -- 엔티티가 Instant 매핑(TIMESTAMP_UTC)이라 TIMESTAMPTZ — ddl-auto=validate 통과 조건
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_stock_market CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX'))
);

CREATE TABLE IF NOT EXISTS stock_quotes (
    id               BIGSERIAL      PRIMARY KEY,
    stock_code       VARCHAR(6)     NOT NULL REFERENCES stocks (stock_code),
    base_date        DATE           NOT NULL,
    close_price      NUMERIC(15,2)  NOT NULL,
    open_price       NUMERIC(15,2),
    high_price       NUMERIC(15,2),
    low_price        NUMERIC(15,2),
    prior_day_diff   NUMERIC(15,2),               -- 전일 대비 (금융위 vs)
    fluctuation_rate NUMERIC(8,2),                -- 등락률 % (금융위 fltRt)
    volume           NUMERIC(20,0),               -- 거래량 (금융위 trqu)
    trade_amount     NUMERIC(24,0),               -- 거래대금 원 (금융위 trPrc)
    listed_shares    NUMERIC(20,0),               -- 상장주식수 (금융위 lstgStCnt)
    market_cap       NUMERIC(24,0),               -- 시가총액 원 (금융위 mrktTotAmt)
    source           VARCHAR(10)    NOT NULL,      -- SEED(근사 샘플) / KRX(실데이터)
    synced_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_sq_stock_date UNIQUE (stock_code, base_date),
    CONSTRAINT chk_sq_source CHECK (source IN ('SEED', 'KRX'))
);

CREATE INDEX IF NOT EXISTS idx_sq_code_date ON stock_quotes (stock_code, base_date DESC);
