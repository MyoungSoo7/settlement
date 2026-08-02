package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 조직·멤버 프로젝션 어댑터. 자연키(organization_id / (organization_id,user_id)) 라
 * 우선 조회 후 있으면 갱신·없으면 신규 생성하는 방식으로 UPSERT 한다(카드계정처럼 @Version
 * 낙관적 락을 두지 않음 — 프로젝션은 소스가 이벤트 순서로 이미 직렬화돼 있어 동시 충돌 위험이 낮고,
 * 최후 수신값 승리(last-write-wins)면 충분하다).
 */
@Component
public class OrgProjectionPersistenceAdapter implements LoadOrgProjectionPort, SaveOrgProjectionPort {

    private final SpringDataOrgProjectionRepository orgRepository;
    private final SpringDataOrgMemberProjectionRepository memberRepository;

    public OrgProjectionPersistenceAdapter(SpringDataOrgProjectionRepository orgRepository,
                                           SpringDataOrgMemberProjectionRepository memberRepository) {
        this.orgRepository = orgRepository;
        this.memberRepository = memberRepository;
    }

    @Override
    public Optional<LoadOrgProjectionPort.OrgView> findOrg(Long organizationId) {
        return orgRepository.findById(organizationId).map(OrgProjectionJpaEntity::toView);
    }

    @Override
    public Optional<OrgRole> findMemberRole(Long organizationId, Long userId) {
        return memberRepository.findActiveMember(organizationId, userId)
                .map(m -> OrgRole.from(m.getRole()));
    }

    @Override
    public void saveOrg(Long organizationId, String name, String type, String externalRef) {
        OrgProjectionJpaEntity entity = orgRepository.findById(organizationId).orElse(null);
        if (entity == null) {
            orgRepository.save(new OrgProjectionJpaEntity(organizationId, name, type, externalRef));
            return;
        }
        entity.update(name, type, externalRef);
        orgRepository.save(entity);
    }

    @Override
    public void upsertMember(Long organizationId, Long userId, String role, Long membershipId) {
        OrgMemberProjectionJpaEntity.OrgMemberProjectionId id =
                new OrgMemberProjectionJpaEntity.OrgMemberProjectionId(organizationId, userId);
        OrgMemberProjectionJpaEntity entity = memberRepository.findById(id).orElse(null);
        if (entity == null) {
            OrgMemberProjectionJpaEntity fresh =
                    new OrgMemberProjectionJpaEntity(organizationId, userId, role, true);
            fresh.setMembershipId(membershipId);
            memberRepository.save(fresh);
            return;
        }
        if (isStale(membershipId, entity)) {
            return;   // 과거 세대의 늦은 이벤트 — 제거를 되살리면 안 된다
        }
        // 같은 세대인데 이미 제거됨 → REMOVED 는 멤버십의 터미널이라, 같은 세대의 joined/
        // role_changed 는 전부 제거 이전에 발행된 과거 이벤트다. 부활 금지.
        if (entity.getMembershipId() != null && entity.getMembershipId().equals(membershipId)
                && !entity.isActive()) {
            return;
        }
        // 세대 없는(created 경유 OWNER) 등록은 톰스톤을 이기지 못한다 — created 재전송이
        // 제거된 오너를 되살리는 경로를 막는다.
        if (membershipId == null && !entity.isActive()) {
            return;
        }
        entity.setRole(role);
        entity.setActive(true);
        if (membershipId != null) {
            entity.setMembershipId(membershipId);
        }
        memberRepository.save(entity);
    }

    @Override
    public void deactivateMember(Long organizationId, Long userId, Long membershipId) {
        OrgMemberProjectionJpaEntity.OrgMemberProjectionId id =
                new OrgMemberProjectionJpaEntity.OrgMemberProjectionId(organizationId, userId);
        OrgMemberProjectionJpaEntity entity = memberRepository.findById(id).orElse(null);
        if (entity == null) {
            // 제거가 합류보다 먼저 도착 — 역할 미상의 톰스톤을 남겨, 늦게 올 같은 세대
            // joined 가 부활하지 못하게 한다(행이 없으면 늦은 joined 가 신규 활성으로 들어간다).
            OrgMemberProjectionJpaEntity tombstone =
                    new OrgMemberProjectionJpaEntity(organizationId, userId, null, false);
            tombstone.setMembershipId(membershipId);
            memberRepository.save(tombstone);
            return;
        }
        if (isStale(membershipId, entity)) {
            return;   // 재합류(새 세대) 뒤에 도착한 과거 제거 — 무시
        }
        entity.setActive(false);
        if (membershipId != null) {
            entity.setMembershipId(membershipId);
        }
        memberRepository.save(entity);
    }

    /** 저장된 세대보다 낮은(또는 세대 없는 이벤트가 세대 있는 행을 만난) 경우 = 과거 이벤트. */
    private static boolean isStale(Long incoming, OrgMemberProjectionJpaEntity entity) {
        Long stored = entity.getMembershipId();
        if (stored == null) {
            return false;   // V5 이전 레거시 행 — 비교 불가, 새 이벤트를 그대로 적용
        }
        return incoming != null && incoming < stored;
    }
}
