-- 종목 추천 세트 — CEO 메뉴 "투자 추천" 이 서빙하는 규칙 스크리닝 산출물.
--
-- 추천은 예측이 아니라 규칙 스크리닝(재무 R1·R2 / 악재 뉴스 R3 / 시세 위치 R4·R5) 결과이며,
-- 가격 3종(1차매수/손절/익절)은 TradePlan 규칙(현재가 / 평단 -7% / 평단 +20%, KRX 호가단위)이다.
-- (recommended_date, stock_code) UNIQUE = 같은 추천일 세트 안에서 종목 중복 금지 + 재적재 멱등 키.

CREATE TABLE IF NOT EXISTS stock_recommendations (
    id                BIGSERIAL      PRIMARY KEY,
    recommended_date  DATE           NOT NULL,           -- 추천일 (스크리닝 실행일)
    stock_code        VARCHAR(6)     NOT NULL,
    stock_name        VARCHAR(50)    NOT NULL,
    sector            VARCHAR(30)    NOT NULL,
    reason            TEXT           NOT NULL,           -- 추천 이유 (통과 규칙·수치, 출처 병기)
    entry_price       NUMERIC(19, 2) NOT NULL,           -- 1차 매수가 (현재가 밴드)
    stop_loss_price   NUMERIC(19, 2) NOT NULL,           -- 손절가 (평균 매수가 -7%)
    take_profit_price NUMERIC(19, 2) NOT NULL,           -- 1차 익절가 (평균 매수가 +20%)
    display_order     INTEGER        NOT NULL,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_stock_reco_date_code UNIQUE (recommended_date, stock_code),
    CONSTRAINT chk_stock_reco_prices_positive CHECK (
        entry_price > 0 AND stop_loss_price > 0 AND take_profit_price > 0),
    -- 손절 < 1차매수 < 익절 — 규칙 구조 자체를 스키마가 강제
    CONSTRAINT chk_stock_reco_price_order CHECK (
        stop_loss_price < entry_price AND entry_price < take_profit_price)
);

-- 최신 추천일 세트 조회 핫패스 (recommended_date DESC → display_order)
CREATE INDEX IF NOT EXISTS idx_stock_reco_date_order
    ON stock_recommendations (recommended_date DESC, display_order);

-- 2026-07-15 추천 세트 — kakaopay invest-companion periodic-picks 라이브 스크리닝 산출물
-- (DART FY2025 연결 재무 · 네이버 뉴스 30일 악재 스캔 · 시세 streak/52주 위치, 12후보 → 3선정)
INSERT INTO stock_recommendations
    (recommended_date, stock_code, stock_name, sector, reason,
     entry_price, stop_loss_price, take_profit_price, display_order)
VALUES
    ('2026-07-15', '267260', 'HD현대일렉트릭', '전력기기',
     'FY2025 매출 4.08조(+22.8%)·영업이익률 24.4%, 3개년 연속 성장(DART 연결). 규칙 5종 통과, 최근 30일 회사 고유 악재 보도 없음',
     797000, 704000, 908000, 1),
    ('2026-07-15', '068270', '셀트리온', '바이오·제약',
     'FY2025 매출 4.16조(+17.0%)·영업이익률 28.1%, 3개년 연속 성장(DART 연결). 규칙 5종 통과, 악재 보도 없음',
     172800, 151300, 195300, 2),
    ('2026-07-15', '033780', 'KT&G', '필수소비재',
     'FY2025 매출 6.58조(+11.4%)·영업이익 1.34조 흑자(DART 연결). 반도체 쏠림 장세의 경기 방어 축(업종 분산), 규칙 5종 통과',
     171000, 149800, 193300, 3)
ON CONFLICT (recommended_date, stock_code) DO NOTHING;
