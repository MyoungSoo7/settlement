-- V2: 시공기사 배정 상세
--
-- 배정된 시공기사(user-service users.id, role=TECHNICIAN)를 reservations 에 연결한다.
-- ASSIGNED → IN_PROGRESS → COMPLETED 동안 채워져 있으며, REQUESTED/CONFIRMED 에선 NULL.
--
-- ★ DB-per-service: technician_id 는 plain 값. users 로의 교차 FK 는 두지 않는다.
--   배정 가능 여부(role=TECHNICIAN & APPROVED)는 technician_view 프로젝션(V3)으로 검증한다.

ALTER TABLE reservation.reservations
    ADD COLUMN IF NOT EXISTS technician_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_reservations_technician
    ON reservation.reservations (technician_id, scheduled_date);

COMMENT ON COLUMN reservation.reservations.technician_id IS
    '배정된 시공기사(user-service users.id, role=TECHNICIAN). ASSIGNED 이상에서 채워짐. 교차 FK 없음.';
