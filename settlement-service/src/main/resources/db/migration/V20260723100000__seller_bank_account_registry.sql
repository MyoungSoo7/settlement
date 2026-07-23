-- V20260723100000: 셀러 지급 계좌 레지스트리 (Seed D1 Part A)
--
-- 무엇을: 셀러가 등록·정정하는 송금 대상 계좌의 정본 테이블. Payout 생성 시 이 레지스트리에서
--   계좌 스냅샷을 떠서 Payout 에 박제한다(RegistrySellerBankAccountAdapter 우선, 미등록 시
--   PlaceholderSellerBankAccountAdapter 폴백). 반송(bounce) 후 재지급이 정정된 계좌를 신선 로드하는 원천.
--
-- 멱등/정본: seller_id PK(셀러당 1행 = upsert). 정정은 같은 행 UPDATE(레지스트리는 append-only 원장이
--   아니라 가변 원천이므로 UPDATE 허용 — payouts/ledger 불변 규칙 대상 아님).
--
-- PII: 계좌번호는 앱단 AES-GCM(enc:v1, PayoutFieldEncryptionConverter) 로 암호화해 account_number_enc(text)
--   에 저장한다 — Payout 지급계좌 암호화와 동일 스킴·동일 키(PAYOUT_ENC_KEY). 예금주명은 평문.

CREATE TABLE public.seller_bank_accounts (
    seller_id          bigint       PRIMARY KEY,
    bank_code          varchar(10)  NOT NULL,
    account_number_enc text         NOT NULL,
    account_holder     varchar(100) NOT NULL,
    updated_at         timestamp    NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.seller_bank_accounts IS '셀러 지급 계좌 레지스트리 — Payout 계좌 스냅샷의 원천 (Seed D1).';
COMMENT ON COLUMN public.seller_bank_accounts.account_number_enc IS '계좌번호 AES-GCM enc:v1 암호문 (앱단 암호화).';
