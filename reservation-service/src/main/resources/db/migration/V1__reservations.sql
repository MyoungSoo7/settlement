-- V1: 시공 예약(Reservation) 도메인  (reservation-service 전용 DB: reservations_db, schema=reservation)
--
-- 업체 회원이 등록하는 마루 시공 예약. 관리자 확인 → 기사 배정 → 시공 → 완료의 출발점.
-- 상태머신: REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED ; → CANCELED
--
-- ★ DB-per-service: company_id/product_id 는 다른 서비스(users/products) 식별자를 plain 값으로만 보관한다.
--   교차 서비스 FK 는 두지 않으며(물리 분리), 참조 무결성은 애플리케이션/이벤트 프로젝션이 담당한다.
--   제품 정보는 예약 시점 스냅샷 컬럼으로 보존해 제품 변경/단종에도 예약 이력이 안전하다.

CREATE TABLE IF NOT EXISTS reservation.reservations (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT       NOT NULL,            -- 예약 등록 업체 회원(user-service users.id, role=COMPANY)
    status              VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',

    -- 시공 일정/현장 정보
    scheduled_date      DATE         NOT NULL,
    site_address        VARCHAR(300) NOT NULL,
    site_password       VARCHAR(50),
    site_manager_name   VARCHAR(100) NOT NULL,
    site_manager_phone  VARCHAR(30)  NOT NULL,

    -- 제품 정보 (다른 서비스 제품 식별자 + 예약 시점 스냅샷)
    product_id          BIGINT,
    wood_species        VARCHAR(100),
    brand               VARCHAR(100),
    product_name        VARCHAR(200),
    product_size        VARCHAR(50),

    -- 시공 정보
    construction_area   NUMERIC(10, 2) NOT NULL,
    field_measured      BOOLEAN      NOT NULL DEFAULT FALSE,
    expansion           BOOLEAN      NOT NULL DEFAULT FALSE,
    expansion_area      NUMERIC(10, 2) NOT NULL DEFAULT 0,
    new_floor           BOOLEAN      NOT NULL DEFAULT FALSE,

    -- 부자재 정보
    baseboard           BOOLEAN      NOT NULL DEFAULT FALSE,
    protection_work     BOOLEAN      NOT NULL DEFAULT FALSE,
    protection_area     NUMERIC(10, 2) NOT NULL DEFAULT 0,

    -- 자동 계산 결과
    protection_fee      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    additional_fee      NUMERIC(12, 2) NOT NULL DEFAULT 0,

    -- 기타
    note                VARCHAR(1000),
    canceled_reason     VARCHAR(500),

    version             BIGINT       NOT NULL DEFAULT 0,  -- 낙관적 락
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- ★ 교차 서비스 FK(users/products) 없음 — DB-per-service. 무결성은 애플리케이션이 보장.
    CONSTRAINT chk_reservations_status
        CHECK (status IN ('REQUESTED', 'CONFIRMED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED')),
    CONSTRAINT chk_reservations_expansion_area
        CHECK (NOT expansion OR expansion_area > 0),
    CONSTRAINT chk_reservations_protection_area
        CHECK (NOT protection_work OR protection_area > 0)
);

CREATE INDEX IF NOT EXISTS idx_reservations_schedule
    ON reservation.reservations (scheduled_date, status);

CREATE INDEX IF NOT EXISTS idx_reservations_company
    ON reservation.reservations (company_id, status, scheduled_date DESC);

COMMENT ON COLUMN reservation.reservations.status IS
    'REQUESTED=접수, CONFIRMED=관리자확인, ASSIGNED=기사배정, IN_PROGRESS=시공중, COMPLETED=완료, CANCELED=취소';
COMMENT ON COLUMN reservation.reservations.protection_fee IS '보양비 — pricing 엔진이 보양 평수 × 단가로 자동 산출';
