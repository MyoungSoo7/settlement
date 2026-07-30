-- V20260730220000: secured_loans 실행 시각 스냅샷 — Phase 2-6 (중도상환)
--
-- 중도상환수수료(잔존기간 비례·경과 3년 면제)의 기산점은 신청일이 아니라 **실행일**이다.
-- 승인이 늦어진 대출이 신청일 기산으로 면제 기간을 앞당겨 받으면 안 되므로 실행 시점을 기록한다.
-- 선정산 loans 가 만기 추적을 위해 disbursed_at 을 후행 추가했던 것과 같은 경로(V20260717 계열).
--
-- 기존 행: NULL 유지 — 컬럼 도입 이전에 실행된 행은 실행 시각을 소급 복원할 수 없다.
-- 도메인(SecuredLoan#feeAnchor)이 NULL 이면 신청 시각(created_at)으로 폴백한다. 신청일이 실행일보다
-- 이르므로 면제 판정이 차주에게 불리해지지 않는 방향의 근사다. NOT NULL 제약은 걸 수 없다.

ALTER TABLE secured_loans
    ADD COLUMN IF NOT EXISTS disbursed_at TIMESTAMP;

COMMENT ON COLUMN secured_loans.disbursed_at IS
    '실행(대출금 지급) 시각 스냅샷 — 중도상환수수료 약정기간의 기산점. 컬럼 도입 이전 실행분은 NULL(도메인이 created_at 폴백).';

-- 실행 이후에만 값이 생기고, 실행 시각이 신청 시각보다 이를 수 없다.
ALTER TABLE secured_loans
    ADD CONSTRAINT chk_secured_loan_disbursed_at
        CHECK (disbursed_at IS NULL OR disbursed_at >= created_at) NOT VALID;
ALTER TABLE secured_loans VALIDATE CONSTRAINT chk_secured_loan_disbursed_at;
