package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort;
import github.lms.lemuel.card.application.port.out.SaveOrgProjectionPort;
import github.lms.lemuel.card.domain.OrgRole;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 조직·멤버 프로젝션 영속 어댑터 — PK 기준 JPA merge 로 멱등 upsert 를 구현한다.
 *
 * <p>비활성화는 행 삭제가 아니라 {@code active=false} 툼스톤이다. 한 번도 적재된 적 없는
 * 멤버의 제거 이벤트도 툼스톤을 남겨, 순서 역전·리플레이에도 "제거됨"이 보존된다.
 */
@Component
public class OrgProjectionPersistenceAdapter implements LoadOrgProjectionPort, SaveOrgProjectionPort {

    /** 툼스톤 행의 역할 표기 — 실제 역할을 모른 채 제거만 안 사실을 남길 때 쓴다. */
    private static final String TOMBSTONE_ROLE = "STAFF";

    private final SpringDataOrgProjectionRepository orgRepository;
    private final SpringDataOrgMemberProjectionRepository memberRepository;

    public OrgProjectionPersistenceAdapter(SpringDataOrgProjectionRepository orgRepository,
                                           SpringDataOrgMemberProjectionRepository memberRepository) {
        this.orgRepository = orgRepository;
        this.memberRepository = memberRepository;
    }

    @Override
    public void upsertOrg(Long organizationId, String name, String type, String externalRef) {
        orgRepository.save(new OrgProjectionJpaEntity(organizationId, name, type, externalRef, Instant.now()));
    }

    @Override
    public void upsertMember(Long organizationId, Long userId, OrgRole role) {
        memberRepository.save(new OrgMemberProjectionJpaEntity(
                organizationId, userId, role.name(), true, Instant.now()));
    }

    @Override
    public void deactivateMember(Long organizationId, Long userId) {
        String role = memberRepository.findById(new OrgMemberProjectionJpaEntity.MemberKey(organizationId, userId))
                .map(OrgMemberProjectionJpaEntity::getRole)
                .orElse(TOMBSTONE_ROLE);
        memberRepository.save(new OrgMemberProjectionJpaEntity(
                organizationId, userId, role, false, Instant.now()));
    }

    @Override
    public Optional<OrgView> findOrg(Long organizationId) {
        return orgRepository.findById(organizationId)
                .map(e -> new OrgView(e.getOrganizationId(), e.getType(), e.getExternalRef()));
    }

    @Override
    public Optional<OrgRole> findMemberRole(Long organizationId, Long userId) {
        return memberRepository.findByOrganizationIdAndUserIdAndActiveTrue(organizationId, userId)
                .map(e -> OrgRole.valueOf(e.getRole()));
    }
}
