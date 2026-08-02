-- V3: 공용 감사 로그 테이블 (shared-common common.audit) — 처음부터 월별 파티셔닝 + append-only
--
-- card-service 는 루트 컴포넌트 스캔으로 shared-common 의 AuditLogJpaEntity(@Table audit_logs) 를
-- 포함하므로, 자체 DB 에도 동일 테이블이 필요하다 (ddl-auto=validate 정합).
--
-- ★ organization 은 비파티션 V3 로 먼저 만들고 V20260717100000 에서 나중에 파티션 부모로 전환
--   (RENAME → 신규 파티션드 부모 생성 → 데이터 이관 → 구 테이블 DROP) 했지만, card 는 신규 서비스라
--   그 리네임/이관 단계가 필요 없다 — 처음부터 파티션드 부모 + append-only 트리거 + 유지보수 함수
--   2종으로 작성한다 (organization/investment/loan 의 최종 표준 상태와 동일 스키마).

-- 1) 파티션드 부모. PK 는 (id, created_at) — 파티션 키를 PK 에 포함하면서 id 전역 유일성 유지.
CREATE TABLE audit_logs (
    id              BIGSERIAL,
    actor_id        BIGINT,
    actor_email     VARCHAR(255),
    action          VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(50),
    resource_id     VARCHAR(64),
    detail_json     JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- 2) 월별 파티션 2026_01 ~ 2028_12 + DEFAULT (organization 최종 런웨이와 동일 구간).
CREATE TABLE audit_logs_2026_01 PARTITION OF audit_logs FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE audit_logs_2026_02 PARTITION OF audit_logs FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE audit_logs_2026_03 PARTITION OF audit_logs FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE audit_logs_2026_04 PARTITION OF audit_logs FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE audit_logs_2026_05 PARTITION OF audit_logs FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE audit_logs_2026_06 PARTITION OF audit_logs FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE audit_logs_2026_07 PARTITION OF audit_logs FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE audit_logs_2026_08 PARTITION OF audit_logs FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE audit_logs_2026_09 PARTITION OF audit_logs FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE audit_logs_2026_10 PARTITION OF audit_logs FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit_logs_2026_11 PARTITION OF audit_logs FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit_logs_2026_12 PARTITION OF audit_logs FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE audit_logs_2027_01 PARTITION OF audit_logs FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE audit_logs_2027_02 PARTITION OF audit_logs FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE audit_logs_2027_03 PARTITION OF audit_logs FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE audit_logs_2027_04 PARTITION OF audit_logs FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE audit_logs_2027_05 PARTITION OF audit_logs FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE audit_logs_2027_06 PARTITION OF audit_logs FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE audit_logs_2027_07 PARTITION OF audit_logs FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE audit_logs_2027_08 PARTITION OF audit_logs FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');
CREATE TABLE audit_logs_2027_09 PARTITION OF audit_logs FOR VALUES FROM ('2027-09-01') TO ('2027-10-01');
CREATE TABLE audit_logs_2027_10 PARTITION OF audit_logs FOR VALUES FROM ('2027-10-01') TO ('2027-11-01');
CREATE TABLE audit_logs_2027_11 PARTITION OF audit_logs FOR VALUES FROM ('2027-11-01') TO ('2027-12-01');
CREATE TABLE audit_logs_2027_12 PARTITION OF audit_logs FOR VALUES FROM ('2027-12-01') TO ('2028-01-01');
CREATE TABLE audit_logs_2028_01 PARTITION OF audit_logs FOR VALUES FROM ('2028-01-01') TO ('2028-02-01');
CREATE TABLE audit_logs_2028_02 PARTITION OF audit_logs FOR VALUES FROM ('2028-02-01') TO ('2028-03-01');
CREATE TABLE audit_logs_2028_03 PARTITION OF audit_logs FOR VALUES FROM ('2028-03-01') TO ('2028-04-01');
CREATE TABLE audit_logs_2028_04 PARTITION OF audit_logs FOR VALUES FROM ('2028-04-01') TO ('2028-05-01');
CREATE TABLE audit_logs_2028_05 PARTITION OF audit_logs FOR VALUES FROM ('2028-05-01') TO ('2028-06-01');
CREATE TABLE audit_logs_2028_06 PARTITION OF audit_logs FOR VALUES FROM ('2028-06-01') TO ('2028-07-01');
CREATE TABLE audit_logs_2028_07 PARTITION OF audit_logs FOR VALUES FROM ('2028-07-01') TO ('2028-08-01');
CREATE TABLE audit_logs_2028_08 PARTITION OF audit_logs FOR VALUES FROM ('2028-08-01') TO ('2028-09-01');
CREATE TABLE audit_logs_2028_09 PARTITION OF audit_logs FOR VALUES FROM ('2028-09-01') TO ('2028-10-01');
CREATE TABLE audit_logs_2028_10 PARTITION OF audit_logs FOR VALUES FROM ('2028-10-01') TO ('2028-11-01');
CREATE TABLE audit_logs_2028_11 PARTITION OF audit_logs FOR VALUES FROM ('2028-11-01') TO ('2028-12-01');
CREATE TABLE audit_logs_2028_12 PARTITION OF audit_logs FOR VALUES FROM ('2028-12-01') TO ('2029-01-01');
CREATE TABLE audit_logs_default  PARTITION OF audit_logs DEFAULT;

-- 3) 인덱스 표준 3종.
CREATE INDEX idx_audit_logs_actor_time  ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_logs_resource    ON audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_logs_action_time ON audit_logs (action, created_at DESC);

-- 4) append-only 강제 (UPDATE·DELETE 거부) — 파티션드 부모 BEFORE ROW 트리거는 신규 파티션에도 자동 적용
CREATE OR REPLACE FUNCTION audit_logs_block_modify()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 는 append-only 입니다: % 연산 불가 (감사 로그 변조 차단)', TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_modify();

-- 5) 유지보수 함수 (전 서비스 동일 시그니처)
CREATE OR REPLACE FUNCTION ensure_audit_log_partition(months_ahead int DEFAULT 1)
RETURNS int
LANGUAGE plpgsql
SET search_path = opslab, pg_catalog
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
SET search_path = opslab, pg_catalog
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

COMMENT ON TABLE audit_logs IS '민감 작업 감사 추적. created_at 월별 RANGE 파티션 + append-only 트리거. 선생성=ensure_audit_log_partition, 리텐션=prune_audit_logs(DETACH+DROP, DEFAULT 보호).';
