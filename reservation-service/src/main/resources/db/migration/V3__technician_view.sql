-- V3: 기사 read-projection (Event-Carried State Transfer)
--
-- user-service 가 발행하는 회원 멤버십 변경 이벤트(lemuel.user.membership-changed)를
-- reservation-service 가 consume 하여 이 테이블에 upsert 한다.
-- 기사 배정 검증(ReservationTechnicianPort)은 더 이상 user DB 를 직접 조회하지 않고
-- 이 로컬 프로젝션만 읽는다 — DB-per-service 의 런타임 결합 0.

CREATE TABLE IF NOT EXISTS reservation.technician_view (
    user_id           BIGINT       PRIMARY KEY,           -- user-service users.id
    role              VARCHAR(20)  NOT NULL,
    membership_status VARCHAR(20)  NOT NULL,              -- PENDING/APPROVED/REJECTED/SUSPENDED
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE reservation.technician_view IS
    'user-service 멤버십 이벤트로 동기화되는 기사 자격 프로젝션. 배정 검증 전용 read-model.';
