-- V2: 시드 데이터소스 + 레코드 — 인증키(DATA_GO_KR_API_KEY) 없이도 데모 가능하게 한다.
--
-- 예시 소스: 한국천문연구원 특일정보 getRestDeInfo(공휴일). 실수집이 같은 recordKey
-- (locdate|seq) 로 UNIQUE upsert 하므로 시드는 자연스럽게 실데이터로 대체된다.
-- 레코드는 2026년 법정공휴일 근사 시드(대체공휴일 미포함) — payload 는 실제 API 아이템 형태.

INSERT INTO data_sources (code, name, endpoint, default_params, key_fields, page_size, enabled, description)
VALUES (
    'kasi-rest-days',
    '한국천문연구원 특일정보(공휴일)',
    'https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo',
    '{"solYear":"2026","_type":"json"}',
    'locdate,seq',
    50,
    TRUE,
    '연도별 법정공휴일 — solYear 파라미터를 sync 트리거에서 override 해 다른 연도 수집 가능'
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO data_records (source_id, record_key, payload)
SELECT s.id, r.record_key, r.payload
FROM data_sources s
JOIN (VALUES
    ('20260101|1', '{"dateKind":"01","dateName":"1월1일","isHoliday":"Y","locdate":20260101,"seq":1}'),
    ('20260216|1', '{"dateKind":"01","dateName":"설날","isHoliday":"Y","locdate":20260216,"seq":1}'),
    ('20260217|1', '{"dateKind":"01","dateName":"설날","isHoliday":"Y","locdate":20260217,"seq":1}'),
    ('20260218|1', '{"dateKind":"01","dateName":"설날","isHoliday":"Y","locdate":20260218,"seq":1}'),
    ('20260301|1', '{"dateKind":"01","dateName":"삼일절","isHoliday":"Y","locdate":20260301,"seq":1}'),
    ('20260505|1', '{"dateKind":"01","dateName":"어린이날","isHoliday":"Y","locdate":20260505,"seq":1}'),
    ('20260524|1', '{"dateKind":"01","dateName":"부처님오신날","isHoliday":"Y","locdate":20260524,"seq":1}'),
    ('20260606|1', '{"dateKind":"01","dateName":"현충일","isHoliday":"Y","locdate":20260606,"seq":1}'),
    ('20260815|1', '{"dateKind":"01","dateName":"광복절","isHoliday":"Y","locdate":20260815,"seq":1}'),
    ('20260924|1', '{"dateKind":"01","dateName":"추석","isHoliday":"Y","locdate":20260924,"seq":1}'),
    ('20260925|1', '{"dateKind":"01","dateName":"추석","isHoliday":"Y","locdate":20260925,"seq":1}'),
    ('20260926|1', '{"dateKind":"01","dateName":"추석","isHoliday":"Y","locdate":20260926,"seq":1}'),
    ('20261003|1', '{"dateKind":"01","dateName":"개천절","isHoliday":"Y","locdate":20261003,"seq":1}'),
    ('20261009|1', '{"dateKind":"01","dateName":"한글날","isHoliday":"Y","locdate":20261009,"seq":1}'),
    ('20261225|1', '{"dateKind":"01","dateName":"기독탄신일","isHoliday":"Y","locdate":20261225,"seq":1}')
) AS r(record_key, payload) ON TRUE
WHERE s.code = 'kasi-rest-days'
ON CONFLICT (source_id, record_key) DO NOTHING;
