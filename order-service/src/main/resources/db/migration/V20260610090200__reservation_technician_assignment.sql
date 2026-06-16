-- V20260610090200: 시공기사 배정 상세
--
-- 기존 assign() 은 상태만 ASSIGNED 로 바꿨고 "누가" 배정됐는지 보존하지 않았다.
-- 배정된 시공기사(users.id, role=TECHNICIAN)를 reservations 에 직접 연결한다.
-- ASSIGNED → IN_PROGRESS → COMPLETED 동안 채워져 있으며, REQUESTED/CONFIRMED 에선 NULL.

ALTER TABLE opslab.reservations
    ADD COLUMN IF NOT EXISTS technician_id BIGINT;

ALTER TABLE opslab.reservations
    ADD CONSTRAINT fk_reservations_technician
        FOREIGN KEY (technician_id) REFERENCES opslab.users(id);

-- 기사별 배정 현황 / 일정 조회 (기사 앱 "내 작업" 화면)
CREATE INDEX IF NOT EXISTS idx_reservations_technician
    ON opslab.reservations (technician_id, scheduled_date);

COMMENT ON COLUMN opslab.reservations.technician_id IS
    '배정된 시공기사(users.id, role=TECHNICIAN). ASSIGNED 이상에서 채워짐';
