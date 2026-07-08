-- V2: 시드 — 코스피 대표 20개사 기업 마스터.
--
-- ★ financial-statements-service 의 V2 시드와 동일한 종목코드 세트다 — 두 서비스가 기업
--   식별자(stockCode)를 공용 비즈니스 키로 공유한다(ADR 0023). corp_code 는 시드 단계에서
--   알 수 없어 NULL. 기사(articles)는 시드하지 않는다 — NAVER_CLIENT_ID/SECRET 설정 후
--   POST /admin/company/collect 로 수집한다.

INSERT INTO companies (stock_code, corp_code, name, market) VALUES
    ('005930', NULL, '삼성전자', 'KOSPI'),
    ('000660', NULL, 'SK하이닉스', 'KOSPI'),
    ('373220', NULL, 'LG에너지솔루션', 'KOSPI'),
    ('207940', NULL, '삼성바이오로직스', 'KOSPI'),
    ('005380', NULL, '현대차', 'KOSPI'),
    ('000270', NULL, '기아', 'KOSPI'),
    ('068270', NULL, '셀트리온', 'KOSPI'),
    ('005490', NULL, 'POSCO홀딩스', 'KOSPI'),
    ('035420', NULL, 'NAVER', 'KOSPI'),
    ('035720', NULL, '카카오', 'KOSPI'),
    ('051910', NULL, 'LG화학', 'KOSPI'),
    ('006400', NULL, '삼성SDI', 'KOSPI'),
    ('105560', NULL, 'KB금융', 'KOSPI'),
    ('055550', NULL, '신한지주', 'KOSPI'),
    ('012330', NULL, '현대모비스', 'KOSPI'),
    ('096770', NULL, 'SK이노베이션', 'KOSPI'),
    ('066570', NULL, 'LG전자', 'KOSPI'),
    ('028260', NULL, '삼성물산', 'KOSPI'),
    ('086790', NULL, '하나금융지주', 'KOSPI'),
    ('017670', NULL, 'SK텔레콤', 'KOSPI')
ON CONFLICT (stock_code) DO NOTHING;
