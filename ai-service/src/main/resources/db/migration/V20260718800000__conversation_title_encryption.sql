-- V20260718800000: chat_conversations.title 암호화 수용 확폭 (R5 리뷰 후속, ai)
--
-- [지적 · A-med] chat_messages.content 는 enc:v1 암호화(V20260717400000)됐으나 title 은 "첫 사용자
--   메시지 앞 120자" — 보호 대상과 동일한 발화 내용이 제목 컬럼으로 평문 우회 노출되는 비대칭.
--   (쓰기 전 PII 마스킹 초크포인트는 이미 title 에도 적용 중 — 이번 조치는 at-rest 암호화 대칭화.)
-- [무엇] title VARCHAR(120) → VARCHAR(512) 확폭(암호문 = enc:v1: + Base64(IV||ct+tag), 평문 120자
--   기준 약 250자). 암호화 자체는 content 와 동일한 앱단 컨버터(ChatContentEncryptionConverter,
--   CHAT_ENC_KEY, lazy migration — enc:v1 미접두 기존 행은 평문 그대로 읽힘)가 수행한다.
-- [안전성] VARCHAR 확폭은 rewrite 없는 메타데이터 변경. 도메인 제목 최대 길이(평문 120자)는
--   도메인 계층이 계속 강제 — DB 폭은 암호문 수용치.

ALTER TABLE chat_conversations
    ALTER COLUMN title TYPE VARCHAR(512);

COMMENT ON COLUMN chat_conversations.title IS
    '대화 제목(첫 사용자 발화 앞 120자, 마스킹 후). 앱단 AES-GCM enc:v1 암호화 — 미접두 값은 레거시 평문(lazy migration). 평문 길이 상한은 도메인(120자), 컬럼 폭은 암호문 수용치(512).';
