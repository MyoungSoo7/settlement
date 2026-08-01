package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataOrgMemberProjectionRepository
        extends JpaRepository<OrgMemberProjectionJpaEntity, OrgMemberProjectionJpaEntity.MemberKey> {

    Optional<OrgMemberProjectionJpaEntity> findByOrganizationIdAndUserIdAndActiveTrue(Long organizationId, Long userId);
}
