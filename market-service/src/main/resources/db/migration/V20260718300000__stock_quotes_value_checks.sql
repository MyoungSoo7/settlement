-- V20260718300000: stock_quotes 값 무결성 CHECK — R4 리뷰 후속 (market)
--
-- [지적 · B-med] 원장(amount>0)과 달리 시세 테이블엔 도메인 CHECK 가 전무 — 0/음수 종가·음수 거래량이
--   물리적으로 허용되는 상태였다. 피드값 보존 원칙(market-quotes-rules)과 충돌하지 않는 최소 하한만 강제:
--   · close_price > 0 — 0/음수 종가는 어떤 피드에서도 정상 값이 아니다(정지 종목도 직전 종가 유지).
--   · open/high/low ≥ 0 (NULL 허용) — 결측 필드가 0 으로 오는 피드 방어적 수용, 음수만 차단.
--   · 수량·금액류(volume/trade_amount/listed_shares/market_cap) ≥ 0 (NULL 허용).
--   · prior_day_diff·fluctuation_rate 는 음수가 정상(하락) — CHECK 제외.
-- NOT VALID→VALIDATE 2단(시드 SEED 데이터는 base_price 파생 양수라 통과).

ALTER TABLE stock_quotes
    ADD CONSTRAINT chk_sq_close_price_positive
        CHECK (close_price > 0) NOT VALID,
    ADD CONSTRAINT chk_sq_prices_non_negative
        CHECK ((open_price IS NULL OR open_price >= 0)
           AND (high_price IS NULL OR high_price >= 0)
           AND (low_price  IS NULL OR low_price  >= 0)) NOT VALID,
    ADD CONSTRAINT chk_sq_quantities_non_negative
        CHECK ((volume        IS NULL OR volume        >= 0)
           AND (trade_amount  IS NULL OR trade_amount  >= 0)
           AND (listed_shares IS NULL OR listed_shares >= 0)
           AND (market_cap    IS NULL OR market_cap    >= 0)) NOT VALID;
ALTER TABLE stock_quotes VALIDATE CONSTRAINT chk_sq_close_price_positive;
ALTER TABLE stock_quotes VALIDATE CONSTRAINT chk_sq_prices_non_negative;
ALTER TABLE stock_quotes VALIDATE CONSTRAINT chk_sq_quantities_non_negative;
