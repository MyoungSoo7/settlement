-- V20260715153000: chat_conversations 도메인 불변식 CHECK + 미비 인덱스 보강 — 3인 DB 리뷰 지적 반영
--
-- 설계 근거:
--   리뷰는 "role/status enum 등 도메인 대조 CHECK 보강"을 요청했으나, 실제 도메인 대조 결과:
--     · role(USER/ASSISTANT) 은 chat_messages 소관이며 이미 V1 의 chk_message_role 로 강제됨
--       (chat_messages 는 E3 레인 파티셔닝 대상 — 본 파일에서 건드리지 않음).
--     · Conversation 애그리게잇(도메인)에는 status 개념 자체가 없다 — 컬럼도 없어 CHECK 대상이 아니다.
--   따라서 chat_conversations 가 실제로 가진 컬럼의 도메인 불변식만 방어선으로 심는다:
--     · message_count 는 0 에서 시작해 왕복당 +2 (Conversation.recordExchange) → 음수 불가.
--     · title 은 Conversation.deriveTitle 이 비어 있으면 예외 → 공백 전용 불가.
--   또한 오래된 대화 아카이빙/리텐션 스캔용으로 created_at 단독 인덱스를 보강한다(V1 의
--   (user_id, last_message_at) 인덱스는 사용자별 최근순 조회용이라 전역 나이 스캔에는 비효율).

-- 도메인 불변식 CHECK (ddl-auto=validate 는 CHECK 를 검증하지 않아 엔티티 변경 불필요)
ALTER TABLE chat_conversations
    ADD CONSTRAINT chk_conversation_message_count_nonneg CHECK (message_count >= 0);
ALTER TABLE chat_conversations
    ADD CONSTRAINT chk_conversation_title_not_blank CHECK (length(btrim(title)) > 0);

-- 오래된 대화 아카이빙/리텐션 스캔용 (전 사용자 횡단, created_at 단독)
CREATE INDEX IF NOT EXISTS idx_conversations_created
    ON chat_conversations (created_at);

COMMENT ON COLUMN chat_conversations.message_count IS
    '대화 내 메시지 수. 왕복당 +2 (Conversation.recordExchange). chk_conversation_message_count_nonneg 로 음수 차단.';
