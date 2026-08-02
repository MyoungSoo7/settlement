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
    public void upsertMember(Long organizationId, Long userId, String role) {
        OrgMemberProjectionJpaEntity.OrgMemberProjectionId id =
                new OrgMemberProjectionJpaEntity.OrgMemberProjectionId(organizationId, userId);
        OrgMemberProjectionJpaEntity entity = memberRepository.findById(id).orElse(null);
        if (entity == null) {
            memberRepository.save(new OrgMemberProjectionJpaEntity(organizationId, userId, role, true));
            return;
        }
        entity.setRole(role);
        entity.setActive(true);
        memberRepository.save(entity);
    }

    @Override
    public void deactivateMember(Long organizationId, Long userId) {
        OrgMemberProjectionJpaEntity.OrgMemberProjectionId id =
                new OrgMemberProjectionJpaEntity.OrgMemberProjectionId(organizationId, userId);
        memberRepository.findById(id).ifPresent(m -> {
            m.setActive(false);
            memberRepository.save(m);
        });
        // 프로젝션에 없는 멤버의 제거 이벤트는 무해한 no-op — 이벤트 도착 순서가 어긋난 경우를
        // 방어한다(예: created/member_joined 유실·지연 상태에서 member_removed 가 먼저 옴).
    }
}
