-- V20260718230000: account_entries.owner_id 다형 자연키 형식 CHECK (R5 리뷰 후속, account)
--
-- [지적 · A-low] owner_id VARCHAR(64)는 owner_type 에 따라 다른 도메인 값을 담는 다형 자연키다:
--   SELLER → sellerId(Long 숫자 문자열), CORPORATE → stockCode(거래소 6자리 코드).
--   조합 형식 CHECK 가 없어 타입-값 불일치(예: SELLER 에 종목코드)가 물리 허용되던 것을 강제한다.
-- [형식 근거] SELLER 는 이벤트 페이로드의 Long sellerId → '^[0-9]+$'. CORPORATE 는 companies.stock_code
--   6자리(KRX 표준 — 일부 시장의 영문 혼용 대비 [0-9A-Z]) → '^[0-9A-Z]{6}$'.
--   (AccountEntry 팩토리 6종의 실제 인자 전수 확인: SELLER=sellerId 5종 / CORPORATE=stockCode 1종.)

ALTER TABLE account_entries
    ADD CONSTRAINT chk_account_entry_owner_id_format
        CHECK (
            (owner_type = 'SELLER'    AND owner_id ~ '^[0-9]+$')
         OR (owner_type = 'CORPORATE' AND owner_id ~ '^[0-9A-Z]{6}$')
        ) NOT VALID;
ALTER TABLE account_entries VALIDATE CONSTRAINT chk_account_entry_owner_id_format;

COMMENT ON COLUMN account_entries.owner_id IS
    '다형 자연키 — SELLER=sellerId(숫자), CORPORATE=stockCode(6자리). 형식은 chk_account_entry_owner_id_format 이 강제.';
