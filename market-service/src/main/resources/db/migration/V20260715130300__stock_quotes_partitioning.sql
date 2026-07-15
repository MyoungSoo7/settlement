-- V20260715130300: stock_quotes 연별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   일별 시세 시계열은 종목수 × 거래일로 무한 누적된다(수천 종목 × 수년 → 수백만~수천만 행). 조회는
--   대부분 최근 구간(최신가·기간 시리즈)이라 연별 파티션 프루닝이 유효하고, 오래된 연도 정리는
--   DETACH+DROP(메타데이터 연산)으로 처리한다. 시세는 감사·메트릭보다 파티션 세분화 필요가 낮아 연별로 둔다.
-- [왜 base_date 키인가]
--   조회·리텐션이 거래일(base_date) 축이고, 자연키 uq_sq_stock_date(stock_code, base_date) 가 파티션 키를
--   포함하므로 유니크 제약이 파티션드 테이블에서 유지된다(앱 레벨 upsert=findByStockCodeAndBaseDate+save 와 호환).
--   PK 는 (id, base_date) 복합으로 두어 파티션 키를 PK 에 포함하면서 id 전역 유일성·시퀀스 연속성을 유지한다.
--   @GeneratedValue(IDENTITY) 는 기존 BIGSERIAL 시퀀스(stock_quotes_id_seq) 를 DEFAULT nextval 로 재사용.
--   컬럼 이름·타입·순서·NULL 은 V1 과 완전 동일 — ddl-auto=validate 통과.
-- [리텐션 정책]
--   시세 원계열은 장기 참조 가치가 있어 기본 보존은 운영 정책에 위임하고 도구만 제공:
--   prune_stock_quotes(retain_years)=DETACH+DROP(DEFAULT 보호), ensure_stock_quote_partition(years_ahead)=선생성.
-- 기준 스키마: market_service 자체 DB V1__market_core.sql (public 무접두).

-- 1) 기존 테이블·제약·인덱스 리네임 (이름 충돌 회피). FK 는 신규 부모에 새 이름으로 재생성하므로 구 FK 는 방치(구 테이블과 함께 소멸).
ALTER TABLE stock_quotes RENAME TO stock_quotes_old;
ALTER TABLE stock_quotes_old RENAME CONSTRAINT stock_quotes_pkey TO stock_quotes_old_pkey;
ALTER TABLE stock_quotes_old RENAME CONSTRAINT uq_sq_stock_date TO uq_sq_stock_date_old;
ALTER TABLE stock_quotes_old RENAME CONSTRAINT chk_sq_source TO chk_sq_source_old;
ALTER INDEX idx_sq_code_date RENAME TO idx_sq_code_date_old;

-- 2) 파티션드 부모 — 컬럼 구성 V1 과 동일, PK (id, base_date). 기존 시퀀스 재사용으로 연속성 보존.
CREATE TABLE stock_quotes (
    id               BIGINT         NOT NULL DEFAULT nextval('stock_quotes_id_seq'),
    stock_code       VARCHAR(6)     NOT NULL,
    base_date        DATE           NOT NULL,
    close_price      NUMERIC(15,2)  NOT NULL,
    open_price       NUMERIC(15,2),
    high_price       NUMERIC(15,2),
    low_price        NUMERIC(15,2),
    prior_day_diff   NUMERIC(15,2),
    fluctuation_rate NUMERIC(8,2),
    volume           NUMERIC(20,0),
    trade_amount     NUMERIC(24,0),
    listed_shares    NUMERIC(20,0),
    market_cap       NUMERIC(24,0),
    source           VARCHAR(10)    NOT NULL,
    synced_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, base_date),
    CONSTRAINT uq_sq_stock_date UNIQUE (stock_code, base_date),
    CONSTRAINT chk_sq_source CHECK (source IN ('SEED', 'KRX')),
    CONSTRAINT fk_stock_quotes_stock FOREIGN KEY (stock_code) REFERENCES stocks (stock_code)
) PARTITION BY RANGE (base_date);
ALTER SEQUENCE stock_quotes_id_seq OWNED BY stock_quotes.id;

-- 3) 연별 파티션 2024 ~ 2027 + DEFAULT
CREATE TABLE stock_quotes_2024 PARTITION OF stock_quotes FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE stock_quotes_2025 PARTITION OF stock_quotes FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE stock_quotes_2026 PARTITION OF stock_quotes FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE stock_quotes_2027 PARTITION OF stock_quotes FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE stock_quotes_default PARTITION OF stock_quotes DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거 (구 FK·PK·인덱스 소멸, 시퀀스는 소유권 이전으로 생존)
INSERT INTO stock_quotes
    (id, stock_code, base_date, close_price, open_price, high_price, low_price, prior_day_diff,
     fluctuation_rate, volume, trade_amount, listed_shares, market_cap, source, synced_at)
SELECT id, stock_code, base_date, close_price, open_price, high_price, low_price, prior_day_diff,
     fluctuation_rate, volume, trade_amount, listed_shares, market_cap, source, synced_at
FROM stock_quotes_old;
DROP TABLE stock_quotes_old;

-- 5) 인덱스 동형 재생성
CREATE INDEX idx_sq_code_date ON stock_quotes (stock_code, base_date DESC);

-- 6) 유지보수 함수 (append-only 트리거 없음 — 시세는 upsert 로 정정되는 가변 계열)
CREATE OR REPLACE FUNCTION ensure_stock_quote_partition(years_ahead int DEFAULT 1)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    i int;
    yr int;
    part_name text;
    created int := 0;
BEGIN
    FOR i IN 0..years_ahead LOOP
        yr := EXTRACT(YEAR FROM CURRENT_DATE)::int + i;
        part_name := 'stock_quotes_' || yr::text;
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF stock_quotes FOR VALUES FROM (%L) TO (%L)',
                part_name, make_date(yr, 1, 1), make_date(yr + 1, 1, 1));
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_stock_quotes(retain_years int)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    cutoff_year int;
    r record;
    dropped int := 0;
BEGIN
    IF retain_years < 1 THEN
        RAISE EXCEPTION 'retain_years 는 1 이상이어야 합니다 (요청: %)', retain_years;
    END IF;
    cutoff_year := EXTRACT(YEAR FROM CURRENT_DATE)::int - retain_years;
    FOR r IN
        SELECT c.relname AS part_name
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        WHERE p.relname = 'stock_quotes'
          AND c.relname ~ '^stock_quotes_[0-9]{4}$'
    LOOP
        IF right(r.part_name, 4)::int < cutoff_year THEN
            EXECUTE format('ALTER TABLE stock_quotes DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE stock_quotes IS '일별 시세 시계열. base_date 연별 RANGE 파티션. uq_sq_stock_date(stock_code,base_date) 유지. 선생성=ensure_stock_quote_partition, 리텐션=prune_stock_quotes(DETACH+DROP, DEFAULT 보호).';
