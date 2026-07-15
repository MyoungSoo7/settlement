-- V20260718600000: 상장사 마스터 로컬 스냅샷 소유권 명문화 — R4 리뷰 후속 (company)
--
-- [지적 · B-med] companies 가 financial/company/market 3서비스에 복제되고 corp_code 동기화 계약이
--   없다는 지적. DB-per-service 전제상 복제는 정상 — 이 테이블은 뉴스·평판 수집기가 소유하는 로컬
--   스냅샷이며 전사 조인키는 stock_code(불변 자연키)다. corp_code·name 의 서비스 간 일시 불일치는
--   정상 상태, 정합은 stock_code 기준 대사로 확인한다. (ADR 0027 §마스터 데이터 소유권 참조.)

COMMENT ON TABLE companies IS
    '뉴스·평판 수집기가 소유하는 상장사 로컬 스냅샷. 전사 조인키=stock_code(불변 자연키), corp_code/name 은 소스별 최신화로 서비스 간 일시 불일치 허용 — 정합은 stock_code 대사로 확인(ADR 0027).';
COMMENT ON COLUMN companies.corp_code IS
    'DART 고유번호 — 동기화 후 채워짐(nullable). 타 서비스 companies 와의 동기화 계약은 없음(로컬 스냅샷).';
