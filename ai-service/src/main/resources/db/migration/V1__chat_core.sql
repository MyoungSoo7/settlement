-- V1: ai-service 자체 DB(lemuel_ai) — 대화 코어 (docs/design/ai-service-phase1.md §4)
--
-- 대화(conversation)는 사용자 소유의 메시지 스레드. 메시지는 불변 append-only.
-- user_id 는 order-service users.id 의 비즈니스 키 참조만 한다 (FK 없음 — DB-per-service).

CREATE TABLE chat_conversations (
    id               UUID         PRIMARY KEY,           -- 서버 생성 (클라이언트 추측 불가)
    user_id          BIGINT       NOT NULL,
    title            VARCHAR(120) NOT NULL,              -- 첫 사용자 메시지 앞 120자
    message_count    INTEGER      NOT NULL DEFAULT 0,
    last_message_at  TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user_recent
    ON chat_conversations (user_id, last_message_at DESC);

CREATE TABLE chat_messages (
    id               BIGSERIAL    PRIMARY KEY,
    conversation_id  UUID         NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(10)  NOT NULL,              -- USER / ASSISTANT
    content          TEXT         NOT NULL,
    model            VARCHAR(60),                        -- ASSISTANT 만 (응답 생성 모델 스냅샷)
    input_tokens     INTEGER,                            -- ASSISTANT 만 (usage → 사용자/일자별 비용 집계)
    output_tokens    INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_messages_conversation ON chat_messages (conversation_id, id);
