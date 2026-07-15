-- V20260716200200: 지급계좌 PII 암호화 수용 — 컬럼 확폭 + 스킴 문서화 — DB 설계 리뷰 R2 후속 (레인 F2)
--
-- 무엇을: payouts 의 지급계좌 PII 2컬럼을 암호문 수용 가능하도록 확폭한다.
--           - bank_account_number VARCHAR(50)  → VARCHAR(512)
--           - account_holder_name VARCHAR(100) → VARCHAR(512)
-- 왜: 지급계좌번호·예금주명은 평문 저장 시 유출 위험이 큰 PII 다. 앱단에서 AES-GCM(256) 봉투 암호화를
--     적용하는데(JPA AttributeConverter = PayoutFieldEncryptionConverter), 암호문은
--     `enc:v1:` 접두 + Base64(IV||ciphertext||tag) 형태라 원문보다 길어 기존 폭(50/100)을 넘는다.
--
-- ★ 암호화 스킴(정본): AES/GCM/NoPadding, 키=env PAYOUT_ENC_KEY(Base64 32바이트, AES-256),
--     nonce=12바이트 랜덤, 인증태그=128비트. 저장 형식 = 'enc:v1:' || Base64(IV || ciphertext+tag).
-- ★ 레거시 평문 lazy migration: 복호화 시 값이 'enc:v1:' 로 시작하지 않으면 암호화 도입 이전 평문으로
--     간주하고 그대로 반환한다. 기존 행을 일괄 재기록하지 않고, 해당 행이 다시 저장(UPDATE)될 때
--     자연히 암호문으로 전환된다. 즉 확폭만으로 신규 암호문·기존 평문이 한 컬럼에 공존한다.
-- ★ 안전성: VARCHAR 길이 확대는 PostgreSQL 에서 테이블 재기록 없는 메타데이터 변경(안전, 빠름).
--     엔티티 @Column(length=512) 과 일치시켜 ddl-auto=validate 호환 유지.

ALTER TABLE public.payouts
    ALTER COLUMN bank_account_number TYPE varchar(512);

ALTER TABLE public.payouts
    ALTER COLUMN account_holder_name TYPE varchar(512);

COMMENT ON COLUMN public.payouts.bank_account_number IS
    '지급계좌번호(PII). 앱단 AES-GCM(256) 암호화 — 저장형식 enc:v1:Base64(IV||ciphertext+tag). enc:v1 접두 없는 값은 레거시 평문(lazy migration: 다음 저장 시 암호문 전환).';
COMMENT ON COLUMN public.payouts.account_holder_name IS
    '예금주명(PII). 앱단 AES-GCM(256) 암호화 — 저장형식 enc:v1:Base64(IV||ciphertext+tag). enc:v1 접두 없는 값은 레거시 평문(lazy migration: 다음 저장 시 암호문 전환).';
