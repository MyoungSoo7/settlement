-- V20260716309000: stock_quotes 파티션 런웨이 확대 (2028 연별) — 크로스컷 DB 리뷰 F3 (market)
--
-- [설계 근거]
--   stock_quotes(일별 시세)는 base_date 연별 RANGE 파티션이 2027 까지만 선생성돼 있다. 2028 이후 삽입은
--   DEFAULT 파티션으로 흘러들어 프루닝·리텐션 이점이 사라지므로 2028 파티션을 미리 깐다.
--   uq_sq_stock_date(stock_code,base_date)는 부모 상속으로 유지.
-- [⚠ DEFAULT 파티션 트랩]
--   2028 데이터가 이미 DEFAULT 파티션에 적재된 뒤에는 2028 파티션 생성이 실패한다. 이 확대는 데이터가 쌓이기
--   전(현 시점 미래 구간)에 적용돼야 하며, 대용량 운영 전환은 점검창에서 수행한다.
-- [운영 주의 — 근본 해법]
--   ensure_stock_quote_partition(years_ahead) 를 연 1회 크론/스케줄러로 호출해 런웨이를 굴리는 것이 정답.

CREATE TABLE IF NOT EXISTS stock_quotes_2028 PARTITION OF stock_quotes FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
