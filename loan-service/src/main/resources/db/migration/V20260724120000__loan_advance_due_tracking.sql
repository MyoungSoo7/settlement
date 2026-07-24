-- 선정산 대출 만기 추적: 자동 연체/상각 판정 근거.
--
-- 지금까지 loan_advances 는 실행일/만기일이 없어 연체 자동화가 불가능했고(수동 markOverdue/writeOff 뿐),
-- financing_days(선지급일수)는 수수료 계산에만 쓰고 버려졌다. 이제 실행 시점에 disbursed_at 을 찍고
-- due_at = disbursed_at + financing_days 를 확정해, 배치 스캐너가 만기 경과분을 자동 연체/상각시킨다.
--
-- 구(舊) 데이터: financing_days=0(DEFAULT), disbursed_at/due_at=NULL — due_at IS NULL 은 스캐너 대상에서
-- 제외되므로 과거 대출이 자동 연체로 오탐되지 않는다(안전).
ALTER TABLE loan_advances
    ADD COLUMN IF NOT EXISTS financing_days INT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS disbursed_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS due_at         TIMESTAMP;

-- 배치 스캐너 핫패스: 활성(DISBURSED/OVERDUE) 대출을 만기일로 스캔. 부분 인덱스로 종료 상태는 제외.
CREATE INDEX IF NOT EXISTS idx_loan_advances_due_at_active
    ON loan_advances (due_at)
    WHERE status IN ('DISBURSED', 'OVERDUE');
