-- V4: card-service 자체 DB(lemuel_card) — 카드계정·카드 코어
--
-- 셀러 법인의 카드계정(마스터 한도)과 임직원 카드(서브한도)를 둔다.
-- 조직·임직원은 organization-service 소유라 여기서는 비검증 비즈니스 키(organization_id, holder_user_id)로만 참조한다.
-- 핵심 불변식 master_limit >= SUM(sub_limit) 는 DB 제약으로 표현할 수 없어(집계 제약 부재)
-- 애플리케이션이 card_accounts 행 비관적 락 + 합계 재계산으로 강제한다 — CardIssuanceLimitConcurrencyIT 가 이를 증명한다.

CREATE TABLE card_accounts (
    id                   BIGSERIAL      PRIMARY KEY,
    organization_id      BIGINT         NOT NULL,
    seller_id            VARCHAR(64)    NOT NULL,
    status               VARCHAR(20)    NOT NULL DEFAULT 'SCREENING',
    master_limit         NUMERIC(19,2)  NOT NULL DEFAULT 0,

    -- 한도 산정 근거 스냅샷 — 사후에 "왜 이 한도였나"를 재현하기 위해 보존한다.
    screened_at          TIMESTAMPTZ,
    seller_payable_snap  NUMERIC(19,2),
    holdback_payable_snap NUMERIC(19,2),
    applied_ratio        NUMERIC(5,4),
    reputation_grade     VARCHAR(2),
    limit_formula        VARCHAR(200),
    reject_reason        VARCHAR(300),

    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version              BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT chk_card_account_status
        CHECK (status IN ('SCREENING', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'REJECTED')),
    CONSTRAINT chk_card_account_master_limit_non_negative CHECK (master_limit >= 0),
    CONSTRAINT chk_card_account_grade
        CHECK (reputation_grade IS NULL OR reputation_grade IN ('A', 'B', 'C', 'D', 'E'))
);

-- 조직당 카드계정 1개.
CREATE UNIQUE INDEX uq_card_account_org ON card_accounts (organization_id);
-- 재원 재조회(일 1회 재산정)에서 셀러 기준 조회.
CREATE INDEX idx_card_account_seller ON card_accounts (seller_id);
-- 재산정 스케줄러가 ACTIVE 만 훑는다.
CREATE INDEX idx_card_account_status ON card_accounts (status);

CREATE TABLE cards (
    id               BIGSERIAL      PRIMARY KEY,
    card_account_id  BIGINT         NOT NULL REFERENCES card_accounts(id),
    holder_user_id   BIGINT         NOT NULL,
    masked_card_no   VARCHAR(32)    NOT NULL,
    sub_limit        NUMERIC(19,2)  NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'ISSUED',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version          BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT chk_card_status CHECK (status IN ('ISSUED', 'SUSPENDED', 'CANCELED')),
    CONSTRAINT chk_card_sub_limit_non_negative CHECK (sub_limit >= 0)
);

-- ★ 임직원당 활성 카드 1장. CANCELED 는 슬롯을 비우므로 재발급이 가능하다.
--   동시 발급 경쟁의 최종 차단선(선검증 409 + 이 인덱스 이중 방어).
CREATE UNIQUE INDEX uq_card_active_holder
    ON cards (card_account_id, holder_user_id)
    WHERE status <> 'CANCELED';

-- 서브한도 합계 계산(발급·한도변경 핫패스).
CREATE INDEX idx_card_account_status_lookup ON cards (card_account_id, status);
-- 본인 카드 조회(GET /cards/me).
CREATE INDEX idx_card_holder ON cards (holder_user_id, status);

-- ── 조직·멤버·평판 프로젝션 (Task 7 이 채운다) ──
-- organization-service / company-service 소유 데이터의 읽기 전용 복제본.
-- 여기서 판정하는 것은 "이 요청자가 이 조직의 어떤 역할인가" 뿐이다.

CREATE TABLE org_projection (
    organization_id  BIGINT       PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    external_ref     VARCHAR(64),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_org_projection_type CHECK (type IN ('SELLER', 'CORPORATE'))
);

CREATE TABLE org_member_projection (
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    role             VARCHAR(20)  NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (organization_id, user_id),
    CONSTRAINT chk_org_member_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
);

CREATE TABLE reputation_projection (
    seller_id   VARCHAR(64)  PRIMARY KEY,
    grade       VARCHAR(2)   NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_reputation_grade CHECK (grade IN ('A', 'B', 'C', 'D', 'E'))
);

COMMENT ON TABLE card_accounts IS '법인 카드계정. master_limit >= SUM(cards.sub_limit) 는 앱이 비관적 락으로 강제.';
COMMENT ON TABLE cards IS '임직원 카드. 활성 카드는 임직원당 1장(uq_card_active_holder).';
COMMENT ON TABLE org_member_projection IS 'organization-service 이벤트 프로젝션. 권한 판정 전용 — 여기가 낡으면 퇴사자 카드가 살아남는다.';
