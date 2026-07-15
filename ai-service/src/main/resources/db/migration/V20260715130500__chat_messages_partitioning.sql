-- V20260715130500: chat_messages 월별 RANGE 파티셔닝 전환 (확장성 축 보강)
--
-- [왜 파티셔닝인가]
--   챗봇 메시지는 대화마다 무한 append 되는 시계열이라 단일 힙이 급팽창한다. 조회는 대화 단위(최근)이고
--   리텐션도 시간 기준이라 월별 파티션 프루닝이 유효하고, 오래된 메시지 정리는 DETACH+DROP 으로 처리한다.
-- [왜 created_at 키인가]
--   리텐션이 시간 축(created_at)이라 유일 유효 키다. PK 를 (id, created_at) 복합으로 두어 파티션 키를 PK 에
--   포함하면서 id 전역 유일성·시퀀스 연속성을 유지한다. @GeneratedValue(IDENTITY) 는 기존 BIGSERIAL
--   시퀀스(chat_messages_id_seq) 를 DEFAULT nextval 로 재사용. conversation_id FK(ON DELETE CASCADE) 는
--   신규 부모에 재생성 — 대화 삭제 시 파티션 전반 메시지가 정상 캐스케이드된다(그래서 append-only 트리거는 두지 않음).
--   컬럼 이름·타입·순서·NULL 은 V1 과 완전 동일 — ddl-auto=validate 통과.
-- [리텐션 정책]
--   대화 이력은 비용·프라이버시 관점에서 장기 보관 필요가 낮아 리텐션 도구를 제공:
--   prune_chat_messages(retain_months)=DETACH+DROP(DEFAULT 보호), ensure_chat_message_partition(months_ahead)=선생성.
-- 기준 스키마: ai_service 자체 DB V1__chat_core.sql (public 무접두).

-- 1) 기존 테이블·제약·인덱스 리네임 (이름 충돌 회피). FK 는 신규 부모에 새 이름으로 재생성.
ALTER TABLE chat_messages RENAME TO chat_messages_old;
ALTER TABLE chat_messages_old RENAME CONSTRAINT chat_messages_pkey TO chat_messages_old_pkey;
ALTER TABLE chat_messages_old RENAME CONSTRAINT chk_message_role TO chk_message_role_old;
ALTER INDEX idx_messages_conversation RENAME TO idx_messages_conversation_old;

-- 2) 파티션드 부모 — 컬럼 구성 V1 과 동일, PK (id, created_at). 기존 시퀀스 재사용으로 연속성 보존.
CREATE TABLE chat_messages (
    id               BIGINT       NOT NULL DEFAULT nextval('chat_messages_id_seq'),
    conversation_id  UUID         NOT NULL,
    role             VARCHAR(10)  NOT NULL,
    content          TEXT         NOT NULL,
    model            VARCHAR(60),
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at),
    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES chat_conversations (id) ON DELETE CASCADE
) PARTITION BY RANGE (created_at);
ALTER SEQUENCE chat_messages_id_seq OWNED BY chat_messages.id;

-- 3) 월별 파티션 2026_01 ~ 2027_06 + DEFAULT. created_at 는 UTC 저장이라 경계도 +00 고정.
CREATE TABLE chat_messages_2026_01 PARTITION OF chat_messages FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');
CREATE TABLE chat_messages_2026_02 PARTITION OF chat_messages FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');
CREATE TABLE chat_messages_2026_03 PARTITION OF chat_messages FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');
CREATE TABLE chat_messages_2026_04 PARTITION OF chat_messages FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');
CREATE TABLE chat_messages_2026_05 PARTITION OF chat_messages FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');
CREATE TABLE chat_messages_2026_06 PARTITION OF chat_messages FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE chat_messages_2026_07 PARTITION OF chat_messages FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
CREATE TABLE chat_messages_2026_08 PARTITION OF chat_messages FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
CREATE TABLE chat_messages_2026_09 PARTITION OF chat_messages FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
CREATE TABLE chat_messages_2026_10 PARTITION OF chat_messages FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE chat_messages_2026_11 PARTITION OF chat_messages FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE chat_messages_2026_12 PARTITION OF chat_messages FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE chat_messages_2027_01 PARTITION OF chat_messages FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');
CREATE TABLE chat_messages_2027_02 PARTITION OF chat_messages FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');
CREATE TABLE chat_messages_2027_03 PARTITION OF chat_messages FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00');
CREATE TABLE chat_messages_2027_04 PARTITION OF chat_messages FOR VALUES FROM ('2027-04-01 00:00:00+00') TO ('2027-05-01 00:00:00+00');
CREATE TABLE chat_messages_2027_05 PARTITION OF chat_messages FOR VALUES FROM ('2027-05-01 00:00:00+00') TO ('2027-06-01 00:00:00+00');
CREATE TABLE chat_messages_2027_06 PARTITION OF chat_messages FOR VALUES FROM ('2027-06-01 00:00:00+00') TO ('2027-07-01 00:00:00+00');
CREATE TABLE chat_messages_default  PARTITION OF chat_messages DEFAULT;

-- 4) 데이터 이관 후 구 테이블 제거 (구 FK·PK·인덱스 소멸, 시퀀스는 소유권 이전으로 생존)
INSERT INTO chat_messages
    (id, conversation_id, role, content, model, input_tokens, output_tokens, created_at)
SELECT id, conversation_id, role, content, model, input_tokens, output_tokens, created_at
FROM chat_messages_old;
DROP TABLE chat_messages_old;

-- 5) 인덱스 동형 재생성
CREATE INDEX idx_messages_conversation ON chat_messages (conversation_id, id);

-- 6) 유지보수 함수 (append-only 트리거 없음 — 대화 삭제 시 ON DELETE CASCADE 가 메시지를 지워야 함)
CREATE OR REPLACE FUNCTION ensure_chat_message_partition(months_ahead int DEFAULT 1)
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
        part_name   := 'chat_messages_' || to_char(start_month, 'YYYY_MM');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF chat_messages FOR VALUES FROM (%L) TO (%L)',
                part_name, start_month::timestamptz, end_month::timestamptz);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

CREATE OR REPLACE FUNCTION prune_chat_messages(retain_months int)
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
        WHERE p.relname = 'chat_messages'
          AND c.relname ~ '^chat_messages_[0-9]{4}_[0-9]{2}$'
    LOOP
        IF to_date(right(r.part_name, 7), 'YYYY_MM') < cutoff THEN
            EXECUTE format('ALTER TABLE chat_messages DETACH PARTITION %I', r.part_name);
            EXECUTE format('DROP TABLE %I', r.part_name);
            dropped := dropped + 1;
        END IF;
    END LOOP;
    RETURN dropped;
END;
$$;

COMMENT ON TABLE chat_messages IS '챗봇 메시지 시계열. created_at 월별 RANGE 파티션. conversation_id FK(ON DELETE CASCADE) 유지. 선생성=ensure_chat_message_partition, 리텐션=prune_chat_messages(DETACH+DROP, DEFAULT 보호).';
