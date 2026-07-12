-- V1: organization-service 자체 DB(lemuel_organization) — 조직·멤버십 코어
--
-- 셀러/기업을 하나의 조직(Organization)으로 관리하고, 그 안의 멤버(user)·역할·가입 라이프사이클을 둔다.
-- 조직 식별은 자체 orgId(PK) + 외부 비즈니스 키(external_ref: sellerId 또는 stockCode, nullable).
-- 멤버는 user_id(비검증 비즈니스 키)로 참조한다 — user 존재 검증은 범위 밖(타 위성 서비스 패턴).

CREATE TABLE organizations (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    type          VARCHAR(20)  NOT NULL,                    -- SELLER / CORPORATE
    external_ref  VARCHAR(64),                              -- sellerId 또는 stockCode (nullable, 비검증 참조)
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / SUSPENDED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0,          -- @Version (동시 수정 경쟁 방지)

    CONSTRAINT chk_org_type   CHECK (type   IN ('SELLER', 'CORPORATE')),
    CONSTRAINT chk_org_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX idx_org_external_ref ON organizations (external_ref);

CREATE TABLE memberships (
    id               BIGSERIAL    PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    user_id          BIGINT       NOT NULL,                  -- 비검증 비즈니스 키
    role             VARCHAR(20)  NOT NULL,                  -- OWNER / MANAGER / STAFF
    status           VARCHAR(20)  NOT NULL DEFAULT 'INVITED',-- INVITED / ACTIVE / SUSPENDED / REMOVED
    invited_by       BIGINT,                                 -- 초대 주체 user_id (생성자 자동 OWNER 는 self)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_member_role   CHECK (role   IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT chk_member_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED'))
);

-- ★ 핵심 불변식: 같은 조직에 같은 user 의 "활성 슬롯"(초대 대기 INVITED + 참여 ACTIVE)은 최대 1건.
--   SUSPENDED/REMOVED 는 슬롯을 비우므로 재초대가 가능하다. 동시 초대 경쟁은 이 인덱스가 최종 차단한다.
CREATE UNIQUE INDEX uq_membership_active
    ON memberships (organization_id, user_id)
    WHERE status IN ('INVITED', 'ACTIVE');

-- 조직별 멤버 목록 조회
CREATE INDEX idx_membership_org ON memberships (organization_id, status);
-- 특정 user 가 속한 조직 조회 (인가 판정 시 caller 역할 lookup)
CREATE INDEX idx_membership_user ON memberships (user_id, status);
