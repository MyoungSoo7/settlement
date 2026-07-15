-- V20260718400000: TEXT 저장 의도 명문화 — R4 리뷰 후속 (commondata)
--
-- [지적 · B-low] data_records.payload·data_sources.default_params 가 JSONB 아닌 TEXT — 타 서비스
--   (audit detail_json 등 JSONB)와 비대칭으로 보인다는 지적. 이는 누락이 아니라 커넥터 도메인 규칙
--   (commondata-connector-rules: payload 원문 보존)에 따른 의도된 선택이다:
--   · payload 는 외부 공공데이터 응답의 **원문 보존**이 1급 요구 — JSONB 캐스팅은 키 순서·공백·중복키를
--     정규화해 원문을 변형하고, JSON 이 아닌 응답(XML·CSV)도 담아야 한다. 표준봉투 파싱 결과는
--     별도 컬럼으로 정형화되고 payload 는 재파싱·감사용 원본이다.
--   · default_params 도 등록자가 입력한 원문 파라미터 문자열의 보존이 우선이며, 서버측 JSON 검증은
--     수집기(파서) 계층 책임이다. JSONB 전환은 엔티티 타입 동반 변경(validate 영향)이 필요해
--     이득 없이 결합만 늘린다.
-- 스키마 변경 없음 — 의도를 COMMENT 로 각인한다.

COMMENT ON COLUMN data_records.payload IS
    '외부 응답 원문(TEXT 의도적 — JSONB 캐스팅은 원문 변형·비JSON 응답 배제. 원문 보존이 재파싱·감사의 전제).';
COMMENT ON COLUMN data_sources.default_params IS
    '수집기 전달 원문 파라미터(TEXT 의도적 — 등록 원문 보존, JSON 검증은 수집기 계층 책임).';
