package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataOrgMemberProjectionRepository
        extends JpaRepository<OrgMemberProjectionJpaEntity, OrgMemberProjectionJpaEntity.OrgMemberProjectionId> {

    /** 권한 판정용 — active=true 인 멤버만 반환한다(브리프 리졸루션 #5). */
    @Query("select m from OrgMemberProjectionJpaEntity m "
            + "where m.id.organizationId = :organizationId and m.id.userId = :userId and m.active = true")
    Optional<OrgMemberProjectionJpaEntity> findActiveMember(
            @Param("organizationId") Long organizationId, @Param("userId") Long userId);
}
