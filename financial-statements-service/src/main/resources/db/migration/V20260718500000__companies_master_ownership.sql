-- V20260718500000: 상장사 마스터 로컬 스냅샷 소유권 명문화 — R4 리뷰 후속 (financial)
--
-- [지적 · B-med] companies(상장사 마스터)가 financial/company/market 3서비스에 각각 존재하고
--   corp_code 동기화 계약이 없어 서비스별 독립 드리프트가 가능하다는 지적. DB-per-service 전제상
--   복제 자체는 정상 — 각 테이블은 "해당 서비스 수집기(DART/뉴스/KRX)가 채우는 로컬 스냅샷"이며
--   전사 조인키는 stock_code(거래소 종목코드, 불변 자연키)다. corp_code·name 은 소스별 최신화
--   시점이 달라 일시 불일치가 정상 상태이고, 정합은 stock_code 기준 대사(운영 점검 쿼리)로 확인한다.
--   (ADR 0027 §마스터 데이터 소유권 참조. 이벤트 기반 마스터 프로젝션은 후속 과제로 명기.)

COMMENT ON TABLE companies IS
    'DART 수집기가 소유하는 상장사 로컬 스냅샷. 전사 조인키=stock_code(불변 자연키), corp_code/name 은 소스별 최신화로 서비스 간 일시 불일치 허용 — 정합은 stock_code 대사로 확인(ADR 0027).';
COMMENT ON COLUMN companies.corp_code IS
    'DART 고유번호 — DART 기업 동기화가 채움(nullable). 타 서비스 companies 와의 동기화 계약은 없음(로컬 스냅샷).';
