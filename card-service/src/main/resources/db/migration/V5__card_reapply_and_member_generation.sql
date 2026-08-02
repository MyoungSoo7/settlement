-- V5: PR #204 코드리뷰 반영 2건
--
-- 1) 심사 탈락(REJECTED) 후 재신청 허용 — uq_card_account_org 가 무조건 UNIQUE 라
--    터미널 REJECTED 행이 조직의 유일한 계정 슬롯을 영구 점유했다(도메인 모델
--    "재신청은 새 카드계정이다"와 모순). REJECTED 를 제외한 부분 인덱스로 바꿔
--    비-REJECTED 계정은 여전히 조직당 1개를 강제하되, 탈락 이력은 여러 건 쌓일 수 있게 한다.
DROP INDEX uq_card_account_org;
CREATE UNIQUE INDEX uq_card_account_org ON card_accounts (organization_id)
    WHERE status <> 'REJECTED';

-- 2) 멤버 프로젝션 세대(membershipId) — joined/role_changed/removed 는 서로 다른 토픽이라
--    도착 순서가 보장되지 않는다. REMOVED 는 멤버십 생애주기의 터미널이고 재합류는 항상
--    새(더 큰) membership_id 를 받으므로, 저장된 세대와 비교해 과거 이벤트의 부활을 막는다.
--    V5 이전 적재분은 NULL(비교 열위) — 다음 이벤트 수신 시 자연 채워진다.
ALTER TABLE org_member_projection ADD COLUMN membership_id BIGINT;
-- 제거가 합류보다 먼저 도착하면 역할을 모른 채 톰스톤(active=false)을 남겨야 한다.
ALTER TABLE org_member_projection ALTER COLUMN role DROP NOT NULL;

COMMENT ON COLUMN org_member_projection.membership_id IS
    'organization-service 멤버십 id — 토픽 간 순서 역전 방어용 세대 번호(재합류=새 id, REMOVED=터미널)';
