-- V2: 시드 관측치 (근사치, source='SEED' — ECOS 동기화가 UNIQUE upsert 로 대체)
--
-- ★ 값은 전부 결정적으로 계산한다(random() 금지) — generate_series 로 날짜를 뽑고
--   날짜 파생값(요일/월오프셋)으로 값을 계단·선형 근사한다.

-- 국고채 3년: 최근 90일 중 주말 제외, 2.60% 주변 소폭 변동(결정적 — random() 금지)
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'TREASURY_3Y', d::date,
       2.60 + (EXTRACT(DAY FROM d)::int % 7) * 0.01,
       'SEED'
FROM generate_series(CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE, '1 day') AS d
WHERE EXTRACT(ISODOW FROM d) < 6
ON CONFLICT (indicator_code, observed_date) DO NOTHING;

-- USD_KRW: 동일 패턴, 1380 주변 ±7원
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'USD_KRW', d::date,
       1380 + (EXTRACT(DAY FROM d)::int % 15) - 7,
       'SEED'
FROM generate_series(CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE, '1 day') AS d
WHERE EXTRACT(ISODOW FROM d) < 6
ON CONFLICT (indicator_code, observed_date) DO NOTHING;

-- BASE_RATE: 최근 24개월 월초(월별 지표는 해당 월 1일로 정규화), 3.50% → 2.75%로
-- 6개월마다 0.25%p 씩 인하되는 결정적 계단 근사. n=0 이 이번 달, n=23 이 23개월 전.
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'BASE_RATE',
       (date_trunc('month', CURRENT_DATE) - (n || ' months')::interval)::date,
       3.50 - 0.25 * FLOOR((23 - n) / 6.0),
       'SEED'
FROM generate_series(0, 23) AS n
ON CONFLICT (indicator_code, observed_date) DO NOTHING;

-- CPI: 최근 24개월 월초, 2020=100 기준 110에서 매월 +0.2 씩 늘어나는 결정적 선형 근사.
-- n=0 이 이번 달(값이 가장 큼), n=23 이 23개월 전(값이 가장 작음).
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'CPI',
       (date_trunc('month', CURRENT_DATE) - (n || ' months')::interval)::date,
       110 + 0.2 * (23 - n),
       'SEED'
FROM generate_series(0, 23) AS n
ON CONFLICT (indicator_code, observed_date) DO NOTHING;
