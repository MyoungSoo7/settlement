-- V20260715130400: indicator_values 연별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   경제지표 관측치는 지표수 × 관측일로 누적되는 시계열이다. 조회는 대부분 최근 구간(최신값·기간 시리즈)이라
--   연별 파티션 프루닝이 유효하고, 오래된 연도 정리는 DETACH+DROP(메타데이터 연산)으로 처리한다.
-- [왜 observed_date 키인가]
--   조회·리텐션이 관측일(observed_date) 축이고, 자연키 uq_iv_indicator_date(indicator_code, observed_date) 가
--   파티션 키를 포함하므로 유니크 제약이 파티션드 테이블에서 유지된다(앱 레벨 upsert 와 호환).
--   PK 는 (id, observed_date) 복합으로 파티션 키를 PK 에 포함하면서 id 전역 유일성·시퀀스 연속성을 유지한다.
--   @GeneratedValue(IDENTITY) 는 기존 BIGSERIAL 시퀀스(indicator_values_id_seq) 를 DEFAULT nextval 로 재사용.
--   컬럼 이름·타입·순서·NULL 은 V1 과 완전 동일 — ddl-auto=validate 통과.
-- [리텐션 정책]
--   지표 원계열은 장기 참조 가치가 있어 기본 보존은 운영 정책에 위임하고 도구만 제공:
--   prune_indicator_values(retain_years)=DETACH+DROP(DEFAULT 보호), ensure_indicator_value_partition(years_ahead)=선생성.
-- 기준 스키마: economics_service 자체 DB V1__economics_core.sql (public 무접두).

-- 1) 기존 테이블·제약·인덱스 리네임 (이름 충돌 회피). FK 는 신규 부모에 새 이름으로 재생성.
ALTER TABLE indicator_values RENAME TO indicator_values_old;
ALTER TABLE indicator_values_old RENAME CONSTRAINT indicator_values_pkey TO indicator_values_old_pkey;
ALTER TABLE indicator_values_old RENAME CONSTRAINT uq_iv_indicator_date TO uq_iv_indicator_date_old;
ALTER TABLE indicator_values_old RENAME CONSTRAINT chk_iv_source TO chk_iv_source_old;
ALTER INDEX idx_iv_code_date RENAME TO idx_iv_code_date_old;

-- 2) 파티션드 부모 — 컬럼 구성 V1 과 동일, PK (id, observed_date). 기존 시퀀스 재사용으로 연속성 보존.
CREATE TABLE indicator_values (
    id             BIGINT        NOT NULL DEFAULT nextval('indicator_values_id_seq'),
    indicator_code VARCHAR(30)   NOT NULL,
    observed_date  DATE          NOT NULL,
    value          NUMERIC(18,4) NOT NULL,
    source         VARCHAR(10)   NOT NULL,
    synced_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, observed_date),
    CONSTRAINT uq_iv_indicator_date UNIQUE (indicator_code, observed_date),
    CONSTRAINT chk_iv_source CHECK (source IN ('SEED', 'ECOS')),
    CONSTRAINT fk_indicator_values_indicator FOREIGN KEY (indicator_code) REFERENCES indicators (code)
) PARTITION BY RANGE (observed_date);
ALTER SEQUENCE indicator_values_id_seq OWNED BY indicator_values.id;

-- 3) 연별 파티션 2024 ~ 2027 + DEFAULT
CREATE TABLE indicator_values_2024 PARTITION OF indicator_values FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE indicator_values_2025 PARTITION OF indicator_values FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE indicator_values_2026 PARTITION OF indicator_values FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE indicator_values_2027 PARTITION OF indicator_values FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE indicator_values_default PARTITION OF indicator_values DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거
INSERT INTO indicator_values
    (id, indicator_code, observed_date, value, source, synced_at)
SELECT id, indicator_code, observed_date, value, source, synced_at
FROM indicator_values_old;
DROP TABLE indicator_values_old;

-- 5) 인덱스 동형 재생성
CREATE INDEX idx_iv_code_date ON indicator_values (indicator_code, observed_date DESC);

-- 6) 유지보수 함수 (append-only 트리거 없음 — 관측치는 upsert 로 정정되는 가변 계열)
CREATE OR REPLACE FUNCTION ensure_indicator_value_partition(years_ahead int DEFAULT 1)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    i int;
    yr int;
    part_name text;
    created int := 0;
BEGIN
    FOR i IN 0..years_ahead LOOP
        yr := EXTRACT(YEAR FROM CURRENT_DATE)::int + i;
        part_name := 'indicator_values_' || yr::text;
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF indicator_values FOR VALUES FROM (%L) TO (%L)',
                part_name, make_date(yr, 1, 1), make_date(yr + 1, 1, 1));
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_indicator_values(retain_years int)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    cutoff_year int;
    r record;
    dropped int := 0;
BEGIN
    IF retain_years < 1 THEN
        RAISE EXCEPTION 'retain_years 는 1 이상이어야 합니다 (요청: %)', retain_years;
    END IF;
    cutoff_year := EXTRACT(YEAR FROM CURRENT_DATE)::int - retain_years;
    FOR r IN
        SELECT c.relname AS part_name
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        WHERE p.relname = 'indicator_values'
          AND c.relname ~ '^indicator_values_[0-9]{4}$'
    LOOP
        IF right(r.part_name, 4)::int < cutoff_year THEN
            EXECUTE format('ALTER TABLE indicator_values DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE indicator_values IS '경제지표 관측치 시계열. observed_date 연별 RANGE 파티션. uq_iv_indicator_date(indicator_code,observed_date) 유지. 선생성=ensure_indicator_value_partition, 리텐션=prune_indicator_values(DETACH+DROP, DEFAULT 보호).';
