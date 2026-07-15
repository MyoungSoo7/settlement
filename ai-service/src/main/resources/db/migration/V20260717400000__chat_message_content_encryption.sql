-- V20260717400000: 대화 본문 암호화 스킴 문서화 — DB 설계 리뷰 R3 후속(C-med, 레인 G4)
--
-- 무엇을: chat_messages.content(사용자·LLM 발화)에 적용된 앱단 암호화 스킴을 컬럼 주석으로 문서화한다.
--          content 는 TEXT 라 암호문 확장으로 폭 제약이 없어 컬럼 타입/폭 변경은 없다(주석만).
-- 왜: 결제·정산 문의 과정의 사용자 발화는 비정형 PII(이름·주소·연락처 등)를 포함할 수 있다. 카드번호·
--     주민번호는 PiiMasker 초크포인트가 마스킹하지만 나머지는 평문으로 남으므로, settlement 지급계좌와
--     동형의 AES-GCM(256) 봉투 암호화를 JPA AttributeConverter(ChatContentEncryptionConverter)로 적용해
--     저장 시점(at rest) 유출 표면을 줄인다.
--
-- ★ 암호화 스킴(정본): AES/GCM/NoPadding, 키=env CHAT_ENC_KEY(Base64 32바이트, AES-256),
--     nonce=12바이트 랜덤, 인증태그=128비트. 저장 형식 = 'enc:v1:' || Base64(IV || ciphertext+tag).
--     키 미설정 시 부팅 실패(운영 fail-closed) — JWT_SECRET·PAYOUT_ENC_KEY 와 동일 강도.
-- ★ 레거시 평문 lazy migration: 복호화 시 값이 'enc:v1:' 로 시작하지 않으면 암호화 도입 이전 평문으로
--     간주하고 그대로 반환한다. 메시지는 append-only 라 기존 행 재기록은 없고 신규 저장분부터 암호문으로
--     적재된다. 즉 한 컬럼에 신규 암호문·기존 평문이 자연히 공존한다.
-- ★ chat_messages 는 created_at 월별 RANGE 파티션드 부모(V20260715130500). content 는 상속 컬럼이라
--     부모에 대한 COMMENT 로 충분하며, 파티션별 재적용은 불필요하다.

COMMENT ON COLUMN public.chat_messages.content IS
    '대화 본문(비정형 PII 가능). 앱단 AES-GCM(256) 암호화 — 저장형식 enc:v1:Base64(IV||ciphertext+tag). enc:v1 접두 없는 값은 레거시 평문(lazy migration: append-only 라 신규 저장분부터 암호문).';
