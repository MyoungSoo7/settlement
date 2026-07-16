-- 기업대출 소유권 스코핑(IDOR 방지): 신청자(JWT 주체 userId) 보존.
-- 구(舊) 데이터는 소유자 미상(NULL) — 운영(ADMIN/MANAGER) 콘솔에서만 노출된다.
ALTER TABLE corporate_loans
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_corporate_loans_owner_user_id
    ON corporate_loans (owner_user_id, id DESC);
