-- V1: 경제지표 카탈로그 + 관측치 (economics-service 자체 DB)
--
-- indicators       : 지표 정의 카탈로그. 지표 추가 = row 추가 (스키마 변경 없음).
-- indicator_values : 관측치 시계열. (지표, 관측일) UNIQUE upsert 로 SEED → ECOS 대체.
--                    월별(M) 지표의 observed_date 는 해당 월 1일로 정규화해 저장.

CREATE TABLE IF NOT EXISTS indicators (
    code           VARCHAR(30)  PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    unit           VARCHAR(20)  NOT NULL,
    cycle          VARCHAR(1)   NOT NULL,           -- D 일별 / M 월별 (ECOS cycle 과 1:1)
    ecos_stat_code VARCHAR(20)  NOT NULL,
    ecos_item_code VARCHAR(20)  NOT NULL,
    -- 엔티티가 Instant 매핑(TIMESTAMP_UTC)이라 TIMESTAMPTZ — ddl-auto=validate 통과 조건
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_indicator_cycle CHECK (cycle IN ('D', 'M'))
);

CREATE TABLE IF NOT EXISTS indicator_values (
    id             BIGSERIAL     PRIMARY KEY,
    indicator_code VARCHAR(30)   NOT NULL REFERENCES indicators (code),
    observed_date  DATE          NOT NULL,
    value          NUMERIC(18,4) NOT NULL,
    source         VARCHAR(10)   NOT NULL,          -- SEED(근사 샘플) / ECOS(실데이터)
    synced_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_iv_indicator_date UNIQUE (indicator_code, observed_date),
    CONSTRAINT chk_iv_source CHECK (source IN ('SEED', 'ECOS'))
);

CREATE INDEX IF NOT EXISTS idx_iv_code_date ON indicator_values (indicator_code, observed_date DESC);

-- 초기 카탈로그. ECOS 통계/항목 코드가 틀렸다면 이 row 만 고치면 된다 (코드 수정 불필요).
-- ★ 설계 스펙에는 BASE_RATE 가 M(월별) 로 적혀 있으나 ECOS 722Y001 은 실제로는 일별(D) 통계라
--   여기서 D 로 정정한다 (스펙의 "카탈로그는 데이터라 조정 쉬움" 원칙 범위 내).
INSERT INTO indicators (code, name, unit, cycle, ecos_stat_code, ecos_item_code) VALUES
    ('BASE_RATE',   '한국은행 기준금리', '%',    'D', '722Y001', '0101000'),
    ('TREASURY_3Y', '국고채 3년 금리',   '%',    'D', '817Y002', '010200000'),
    ('USD_KRW',     '원/달러 환율',      'KRW',  'D', '731Y001', '0000001'),
    ('CPI',         '소비자물가지수',    '2020=100', 'M', '901Y009', '0')
ON CONFLICT (code) DO NOTHING;
