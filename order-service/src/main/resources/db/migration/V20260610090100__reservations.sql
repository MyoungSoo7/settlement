-- V20260610090100: 시공 예약(Reservation) 도메인
--
-- 업체 회원이 등록하는 마루 시공 예약. 관리자 확인 → 기사 배정 → 시공 → 완료의 출발점.
-- 상태머신: REQUESTED → CONFIRMED → ASSIGNED → IN_PROGRESS → COMPLETED ; → CANCELED
--
-- 면적 단위는 평(py)으로 통일(NUMERIC 10,2), 금액은 원(KRW, NUMERIC 12,2).
-- 제품 정보는 관리자가 등록한 제품(products) 을 선택하므로 product_id FK + 스냅샷 컬럼 병행
-- (예약 시점의 수종/브랜드/제품명/사이즈를 보존해 추후 제품 변경/단종에도 예약 이력이 안전).

CREATE TABLE IF NOT EXISTS opslab.reservations (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT       NOT NULL,            -- 예약 등록 업체 회원(users.id, role=COMPANY)
    status              VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',

    -- 시공 일정/현장 정보
    scheduled_date      DATE         NOT NULL,            -- 시공 일정
    site_address        VARCHAR(300) NOT NULL,            -- 현장 주소
    site_password       VARCHAR(50),                      -- 현장 출입 비밀번호(공동현관 등)
    site_manager_name   VARCHAR(100) NOT NULL,            -- 현장 담당자
    site_manager_phone  VARCHAR(30)  NOT NULL,            -- 담당자 연락처

    -- 제품 정보 (관리자 등록 제품 선택 + 스냅샷)
    product_id          BIGINT,                           -- 선택된 제품(products.id)
    wood_species        VARCHAR(100),                     -- 마루 수종
    brand               VARCHAR(100),                     -- 브랜드
    product_name        VARCHAR(200),                     -- 제품명
    product_size        VARCHAR(50),                      -- 사이즈

    -- 시공 정보
    construction_area   NUMERIC(10, 2) NOT NULL,          -- 시공 면적(평)
    field_measured      BOOLEAN      NOT NULL DEFAULT FALSE, -- 실측 여부
    expansion           BOOLEAN      NOT NULL DEFAULT FALSE, -- 확장 여부
    expansion_area      NUMERIC(10, 2) NOT NULL DEFAULT 0, -- 확장 면적(평)
    new_floor           BOOLEAN      NOT NULL DEFAULT FALSE, -- 신규 바닥 여부

    -- 부자재 정보
    baseboard           BOOLEAN      NOT NULL DEFAULT FALSE, -- 걸레받이 시공 여부
    protection_work     BOOLEAN      NOT NULL DEFAULT FALSE, -- 보양 작업 여부
    protection_area     NUMERIC(10, 2) NOT NULL DEFAULT 0, -- 보양 평수

    -- 자동 계산 결과 (pricing 엔진 산출값 저장)
    protection_fee      NUMERIC(12, 2) NOT NULL DEFAULT 0, -- 보양비 자동 계산액
    additional_fee      NUMERIC(12, 2) NOT NULL DEFAULT 0, -- 추가 비용

    -- 기타
    note                VARCHAR(1000),                    -- 특이사항
    canceled_reason     VARCHAR(500),

    version             BIGINT       NOT NULL DEFAULT 0,  -- 낙관적 락(동시 수정 방어)
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_reservations_company
        FOREIGN KEY (company_id) REFERENCES opslab.users(id),
    CONSTRAINT fk_reservations_product
        FOREIGN KEY (product_id) REFERENCES opslab.products(id),
    CONSTRAINT chk_reservations_status
        CHECK (status IN ('REQUESTED', 'CONFIRMED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELED')),
    CONSTRAINT chk_reservations_expansion_area
        CHECK (NOT expansion OR expansion_area > 0),       -- 확장이면 확장면적 필수
    CONSTRAINT chk_reservations_protection_area
        CHECK (NOT protection_work OR protection_area > 0) -- 보양작업이면 보양평수 필수
);

-- 관리자 대시보드: 일자별 시공 일정 / 상태별 예약 조회
CREATE INDEX IF NOT EXISTS idx_reservations_schedule
    ON opslab.reservations (scheduled_date, status);

-- 업체 회원: 본인 예약 현황 조회
CREATE INDEX IF NOT EXISTS idx_reservations_company
    ON opslab.reservations (company_id, status, scheduled_date DESC);

COMMENT ON COLUMN opslab.reservations.status IS
    'REQUESTED=접수, CONFIRMED=관리자확인, ASSIGNED=기사배정, '
    'IN_PROGRESS=시공중, COMPLETED=완료, CANCELED=취소';
COMMENT ON COLUMN opslab.reservations.protection_fee IS '보양비 — pricing 엔진이 보양 평수 × 단가로 자동 산출';
