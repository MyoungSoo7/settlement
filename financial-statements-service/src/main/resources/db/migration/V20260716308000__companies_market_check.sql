-- V20260716308000: companies.market 값 도메인 CHECK 추가 — 크로스컷 DB 리뷰 F3 (financial-statements)
--
-- [설계 근거]
--   financial-statements-service 의 companies.market 은 VARCHAR(10) DEFAULT 'KOSPI' 로 값 제약이 없어,
--   market-service 의 chk_stock_market(IN ('KOSPI','KOSDAQ','KONEX'))·company-service 와 비대칭이었다. 같은
--   KRX 시장 구분(금융위 mrktCtg)이므로 동일 집합으로 CHECK 를 걸어 오타·미상 값 유입을 차단한다.
--   * 실데이터·시드 확인: V2/V3 시드는 'KOSPI'(22행)·'KOSDAQ'(11행)만 사용 → 위반 행 없음.
--   * NOT VALID → VALIDATE 2단계로 잠금 최소화.

ALTER TABLE companies
    ADD CONSTRAINT chk_companies_market CHECK (market IN ('KOSPI', 'KOSDAQ', 'KONEX')) NOT VALID;
ALTER TABLE companies VALIDATE CONSTRAINT chk_companies_market;

COMMENT ON CONSTRAINT chk_companies_market ON companies IS
    'KRX 시장 구분 값 제약(market-service chk_stock_market 과 대칭). 허용: KOSPI/KOSDAQ/KONEX.';
