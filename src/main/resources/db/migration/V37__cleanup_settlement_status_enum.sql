-- V37: Settlement status enum 11개 → 5개 정리
-- 신규 5개: REQUESTED, PROCESSING, DONE, FAILED, CANCELED
-- 제거된 레거시 6개: PENDING, WAITING_APPROVAL, APPROVED, REJECTED, CONFIRMED, CALCULATED
--
-- 매핑 의미:
--  - PENDING / WAITING_APPROVAL  → REQUESTED  (정산 대기 중이었던 정산을 신규 흐름의 시작 상태로)
--  - APPROVED / CONFIRMED / CALCULATED → DONE       (확정·완료 의미였던 정산은 모두 종결 상태 DONE)
--  - REJECTED                          → CANCELED   (거부 = 취소)

-- 1. 기존 데이터 변환
UPDATE settlements
SET status = 'REQUESTED', updated_at = NOW()
WHERE status IN ('PENDING', 'WAITING_APPROVAL');

UPDATE settlements
SET status = 'DONE', updated_at = NOW()
WHERE status IN ('APPROVED', 'CONFIRMED', 'CALCULATED');

UPDATE settlements
SET status = 'CANCELED', updated_at = NOW()
WHERE status = 'REJECTED';

-- 2. V22의 레거시 status 부분 인덱스 정리 (인덱스가 참조하는 status 값이 더 이상 존재하지 않음)
DROP INDEX IF EXISTS idx_settlements_approval_status;

-- 3. status 컬럼에 CHECK 제약 추가 (신규 5개만 허용)
ALTER TABLE settlements
    DROP CONSTRAINT IF EXISTS chk_settlements_status;

ALTER TABLE settlements
    ADD CONSTRAINT chk_settlements_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'DONE', 'FAILED', 'CANCELED'));

-- 4. 컬럼 코멘트 갱신
COMMENT ON COLUMN settlements.status IS '정산 상태: REQUESTED|PROCESSING|DONE|FAILED|CANCELED (V37에서 11→5로 정리)';
