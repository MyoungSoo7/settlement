-- V20260716306100: companies.market 값 도메인 CHECK 추가 — 크로스컷 DB 리뷰 F3 (company)
--
-- [설계 근거]
--   companies.market 은 VARCHAR(10) DEFAULT 'KOSPI' 로 값 제약이 없어, market-service 의 stock_master.market
--   (chk_stock_market: IN ('KOSPI','KOSDAQ','KONEX'))과 비대칭이었다. 같은 KRX 시장 구분(금융위 mrktCtg)을
--   가리키는 컬럼이므로 동일 집합으로 CHECK 를 걸어 오타·미상 값 유입을 차단한다.
--   * 실데이터·시드 확인: V2 시드는 전부 'KOSPI'(21행), 테스트도 KOSPI/KOSDAQ 만 사용 → 위반 행 없음.
--   * 도메인 Company 는 market 이 비면 'KOSPI' 로 기본값 처리하므로 NULL/blank 유입은 애초에 없다.
--   * NOT VALID → VALIDATE 2단계로 잠금 최소화.

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_market CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX')) NOT VALID;
ALTER TABLE companies VALIDATE CONSTRAINT chk_companies_market;

COMMENT ON CONSTRAINT chk_companies_market ON companies IS
    'KRX 시장 구분 값 제약(market-service chk_stock_market 과 대칭). 허용: KOSPI/KOSDAQ/KONEX.';
