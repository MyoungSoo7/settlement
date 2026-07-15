-- V20260716100300: common-data-service 감사 로그(audit_logs) 신설 — 3인 DB 리뷰(R2) 지적 반영
--
-- 설계 근거:
--   common-data-service 는 공개 read-only 위성이지만 `/admin/**` 경로가 (1) 데이터소스 등록 —
--   임의 endpoint URL 을 DB 에 등록하는 SSRF 게이트 대상 민감 작업 — 과 (2) 수집 트리거를 노출한다.
--   현재 자체 DB 에 감사 추적 테이블이 전무해 "누가 언제 어떤 endpoint 데이터소스를 등록/수집했나"를
--   포스트모템에서 재구성할 수 없다는 것이 리뷰 지적. SSRF 방어(내부/사설/메타데이터 IP 차단)와 짝을 이루는
--   행위 감사가 없으면 우회 시도 추적이 불가하다. → order-service V34 / company-service V20260715150000 와
--     동일한 shared-common audit 표준 스키마를 commondata 자체 DB(public)에 신설한다.
--
-- E3 표준(감사성 강화형) 채택:
--   · created_at 월별 RANGE 파티션(2026_01~2028_12 + DEFAULT) — 리텐션은 파티션 DROP 으로 O(1).
--   · PK(id, created_at) — 파티션 키가 PK 에 포함되어야 하는 PostgreSQL 제약 충족.
--   · append-only 트리거(BEFORE UPDATE/DELETE RAISE) — 감사 로그 변조·삭제 원천 차단.
--   · ensure/prune 유지보수 함수 — 다음 달 파티션 선생성 + 보존기간 초과 파티션 파기.
--   컬럼 구성은 order-service V34__audit_logs.sql 을 표준으로 복제.
--
-- ★ 주의(스키마 선행): 본 마이그레이션은 테이블만 만든다. commondata 는 shared-common 미의존 위성
--   서비스이므로 AuditLogJpaEntity·기록 서비스(감사 로그 쓰기) Java 배선은 후속 작업이다
--   (현재 매핑 엔티티가 없어 ddl-auto=validate 대상도 아님 — 향후 배선 시 검증 편입).

-- 파티션 부모 (public.audit_logs)
CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL,
    actor_id        BIGINT,                    -- 로그인 유저 id (시스템 액션이면 NULL)
    actor_email     VARCHAR(255),
    action          VARCHAR(50) NOT NULL,      -- AuditAction enum
    resource_type   VARCHAR(50),               -- DataSource / DataCollection / SsrfBlock ...
    resource_id     VARCHAR(64),               -- 문자열 보관 (데이터소스 code·id 등)
    detail_json     JSONB,                     -- 작업 상세 (등록 endpoint URL·차단 사유 등)
    ip_address      VARCHAR(45),               -- IPv4/IPv6
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- 월별 파티션 2026_01 ~ 2028_12 (+ DEFAULT catch-all). R2 지적(런웨이 부족) 반영해 3년치 선생성.
CREATE TABLE IF NOT EXISTS audit_logs_2026_01 PARTITION OF audit_logs FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_02 PARTITION OF audit_logs FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_03 PARTITION OF audit_logs FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_04 PARTITION OF audit_logs FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_05 PARTITION OF audit_logs FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_06 PARTITION OF audit_logs FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_07 PARTITION OF audit_logs FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_08 PARTITION OF audit_logs FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_09 PARTITION OF audit_logs FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_10 PARTITION OF audit_logs FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_11 PARTITION OF audit_logs FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS audit_logs_2026_12 PARTITION OF audit_logs FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_01 PARTITION OF audit_logs FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_02 PARTITION OF audit_logs FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_03 PARTITION OF audit_logs FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_04 PARTITION OF audit_logs FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_05 PARTITION OF audit_logs FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_06 PARTITION OF audit_logs FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_07 PARTITION OF audit_logs FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_08 PARTITION OF audit_logs FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_09 PARTITION OF audit_logs FOR VALUES FROM ('2027-09-01') TO ('2027-10-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_10 PARTITION OF audit_logs FOR VALUES FROM ('2027-10-01') TO ('2027-11-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_11 PARTITION OF audit_logs FOR VALUES FROM ('2027-11-01') TO ('2027-12-01');
CREATE TABLE IF NOT EXISTS audit_logs_2027_12 PARTITION OF audit_logs FOR VALUES FROM ('2027-12-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_01 PARTITION OF audit_logs FOR VALUES FROM ('2028-01-01') TO ('2028-02-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_02 PARTITION OF audit_logs FOR VALUES FROM ('2028-02-01') TO ('2028-03-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_03 PARTITION OF audit_logs FOR VALUES FROM ('2028-03-01') TO ('2028-04-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_04 PARTITION OF audit_logs FOR VALUES FROM ('2028-04-01') TO ('2028-05-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_05 PARTITION OF audit_logs FOR VALUES FROM ('2028-05-01') TO ('2028-06-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_06 PARTITION OF audit_logs FOR VALUES FROM ('2028-06-01') TO ('2028-07-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_07 PARTITION OF audit_logs FOR VALUES FROM ('2028-07-01') TO ('2028-08-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_08 PARTITION OF audit_logs FOR VALUES FROM ('2028-08-01') TO ('2028-09-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_09 PARTITION OF audit_logs FOR VALUES FROM ('2028-09-01') TO ('2028-10-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_10 PARTITION OF audit_logs FOR VALUES FROM ('2028-10-01') TO ('2028-11-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_11 PARTITION OF audit_logs FOR VALUES FROM ('2028-11-01') TO ('2028-12-01');
CREATE TABLE IF NOT EXISTS audit_logs_2028_12 PARTITION OF audit_logs FOR VALUES FROM ('2028-12-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS audit_logs_default PARTITION OF audit_logs DEFAULT;

-- 조회 패턴별 인덱스 3종 (부모에 생성 → 전 파티션 전파)
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_time
    ON audit_logs (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource
    ON audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_time
    ON audit_logs (action, created_at DESC);

-- append-only 강제: UPDATE/DELETE 시도 시 예외 (감사 로그 불변성)
-- ※ 함수명·본문은 E3 파티셔닝 레인 표준(audit_logs_block_modify)과 전 서비스 동일 유지
CREATE OR REPLACE FUNCTION audit_logs_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 는 append-only 입니다: % 연산 불가 (감사 로그 변조 차단)', TG_OP;
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_append_only ON audit_logs;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_modify();

-- 유지보수 함수 (전 서비스 동일 시그니처 — E3 표준)
CREATE OR REPLACE FUNCTION ensure_audit_log_partition(months_ahead int DEFAULT 1)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    i int;
    start_month date;
    end_month date;
    part_name text;
    created int := 0;
BEGIN
    FOR i IN 0..months_ahead LOOP
        start_month := date_trunc('month', CURRENT_DATE + make_interval(months => i))::date;
        end_month   := (start_month + interval '1 month')::date;
        part_name   := 'audit_logs_' || to_char(start_month, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF audit_logs FOR VALUES FROM (%L) TO (%L)',
                part_name, start_month, end_month);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_audit_logs(retain_months int)
RETURNS int
LANGUAGE plpgsql
SET search_path = public, pg_catalog
AS $$
DECLARE
    cutoff date;
    r record;
    dropped int := 0;
BEGIN
    IF retain_months < 1 THEN
        RAISE EXCEPTION 'retain_months 는 1 이상이어야 합니다 (요청: %)', retain_months;
    END IF;
    cutoff := (date_trunc('month', CURRENT_DATE) - make_interval(months => retain_months))::date;
    FOR r IN
        SELECT c.relname AS part_name
        FROM pg_inherits inh
        JOIN pg_class c ON c.oid = inh.inhrelid
        JOIN pg_class p ON p.oid = inh.inhparent
        WHERE p.relname = 'audit_logs'
          AND c.relname ~ '^audit_logs_[0-9]{4}_[0-9]{2}$'
    LOOP
        IF to_date(right(r.part_name, 7), 'YYYY_MM') < cutoff THEN
            EXECUTE format('ALTER TABLE audit_logs DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE audit_logs IS 'commondata 민감 작업(데이터소스 등록=SSRF 게이트·수집 트리거·소스 활성화 토글) 감사 추적. append-only + 월별 파티션. Java 기록 배선은 후속 작업(스키마 선행).';
