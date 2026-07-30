-- V20260730180000: 담보 유형 확장 + 담보권 순위 (Phase 2)
--
-- Phase 1 은 부동산 담보 1종·선순위 없음 전제였다. Phase 2 에서 보증부·금융자산이 합류하고
-- 선순위 채권 차감이 들어오므로 두 축을 반영한다.
--
-- ① type CHECK 를 5종으로 확장. 도메인 CollateralType(REAL_ESTATE·GUARANTEE·DEPOSIT·BOND·EQUITY)이
--    정본이고, 미확장 시 신규 유형 INSERT 가 check_violation(23514)으로 전부 실패한다
--    — loan 원장 ref_type 이 같은 방식으로 두 번 당한 갭이라 유형 추가와 CHECK 확장을 항상 붙여 둔다.
--    신규 3종은 지금까지 INSERT 가 없던 값이라 VALIDATE 가 즉시 통과한다.
ALTER TABLE collaterals DROP CONSTRAINT IF EXISTS chk_collateral_type;
ALTER TABLE collaterals
    ADD CONSTRAINT chk_collateral_type
        CHECK (type IN ('REAL_ESTATE', 'GUARANTEE', 'DEPOSIT', 'BOND', 'EQUITY')) NOT VALID;
ALTER TABLE collaterals VALIDATE CONSTRAINT chk_collateral_type;

-- ② 선순위 채권액. 유효담보가치 = max(0, 평가액 − 선순위)로 한도가 산정된다.
--    기존 행은 Phase 1 전제(선순위 없음)대로 0 이 맞으므로 DEFAULT 0 + NOT NULL 로 안전하게 채운다.
ALTER TABLE collaterals
    ADD COLUMN IF NOT EXISTS senior_claim_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

ALTER TABLE collaterals DROP CONSTRAINT IF EXISTS chk_collateral_senior_claim;
ALTER TABLE collaterals
    ADD CONSTRAINT chk_collateral_senior_claim CHECK (senior_claim_amount >= 0);

COMMENT ON COLUMN collaterals.senior_claim_amount IS
    '선순위 채권액. 유효담보가치 = max(0, appraised_value - senior_claim_amount) — 앞선 근저당 몫은 '
    '이미 타 채권자의 담보력이라 우리 한도에서 제외한다. 평가액 초과도 허용(유효담보가치 0).';

COMMENT ON COLUMN collaterals.type IS
    '담보 유형(도메인 CollateralType 정본). REAL_ESTATE=부동산, GUARANTEE=보증기관 보증서(평가액은 '
    '보증금액), DEPOSIT/BOND/EQUITY=금융자산(재평가·마진콜 대상).';
