-- V2: 시드 종목 + 시세 (근사치, source='SEED' — KRX 동기화가 UNIQUE upsert 로 대체)
--
-- 대표 종목 8개(코스피 7 + 코스닥 1)를 씨딩해 API 키 없이도 CEO 브리핑/invest-copilot 이
-- 동작하게 한다. 상장주식수는 실제 근사, 시세는 전부 결정적으로 계산한다(random() 금지) —
-- generate_series 로 거래일(주말 제외)을 뽑고 날짜 파생값(일자 % 10)으로 종가를 계단 근사한다.

-- 종목 마스터 (시세보다 먼저 — FK)
INSERT INTO stocks (stock_code, isin, name, market) VALUES
    ('005930', 'KR7005930003', '삼성전자',           'KOSPI'),
    ('000660', 'KR7000660001', 'SK하이닉스',         'KOSPI'),
    ('373220', 'KR7373220003', 'LG에너지솔루션',     'KOSPI'),
    ('207940', 'KR7207940008', '삼성바이오로직스',   'KOSPI'),
    ('005380', 'KR7005380001', '현대차',             'KOSPI'),
    ('035420', 'KR7035420009', 'NAVER',              'KOSPI'),
    ('035720', 'KR7035720002', '카카오',             'KOSPI'),
    ('247540', 'KR7247540006', '에코프로비엠',       'KOSDAQ')
ON CONFLICT (stock_code) DO NOTHING;

-- 일별 시세: 최근 90일 중 주말 제외, 종가 = base_price + (일자 % 10 - 5) * step (결정적).
-- market_cap = 종가 × 상장주식수, trade_amount = 종가 × 근사 거래량 — 파생값도 결정적.
INSERT INTO stock_quotes (stock_code, base_date, close_price, open_price, high_price, low_price,
                          prior_day_diff, fluctuation_rate, volume, trade_amount, listed_shares,
                          market_cap, source)
SELECT s.code,
       d::date,
       cl.close_price,
       cl.close_price,
       cl.close_price,
       cl.close_price,
       0,
       0,
       s.base_volume,
       (cl.close_price * s.base_volume)::numeric(24,0),
       s.listed_shares,
       (cl.close_price * s.listed_shares)::numeric(24,0),
       'SEED'
FROM (VALUES
    ('005930',  78000::numeric,  200::numeric, 5969782550::numeric, 12000000::numeric),
    ('000660', 180000::numeric,  500::numeric,  728002365::numeric,  3000000::numeric),
    ('373220', 400000::numeric, 1000::numeric,  234000000::numeric,   800000::numeric),
    ('207940', 780000::numeric, 2000::numeric,   71174000::numeric,   150000::numeric),
    ('005380', 240000::numeric,  700::numeric,  209416191::numeric,   900000::numeric),
    ('035420', 170000::numeric,  500::numeric,  146000000::numeric,  1200000::numeric),
    ('035720',  40000::numeric,  150::numeric,  443000000::numeric,  2500000::numeric),
    ('247540', 180000::numeric,  600::numeric,   97800000::numeric,  1000000::numeric)
) AS s(code, base_price, step, listed_shares, base_volume)
CROSS JOIN generate_series(CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE, '1 day') AS d
CROSS JOIN LATERAL (
    SELECT (s.base_price + (EXTRACT(DAY FROM d)::int % 10 - 5) * s.step)::numeric(15,2) AS close_price
) cl
WHERE EXTRACT(ISODOW FROM d) < 6
ON CONFLICT (stock_code, base_date) DO NOTHING;
