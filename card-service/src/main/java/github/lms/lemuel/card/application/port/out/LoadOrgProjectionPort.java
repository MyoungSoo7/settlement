package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.OrgRole;

import java.util.Optional;

/**
 * 조직·멤버 프로젝션 조회 포트 — 카드계정 개설·발급 인가 판정의 근거.
 */
public interface LoadOrgProjectionPort {

    Optional<OrgView> findOrg(Long organizationId);

    /** 활성 멤버만 — 비활성(조직 이탈) 멤버는 빈 값이다. */
    Optional<OrgRole> findMemberRole(Long organizationId, Long userId);

    record OrgView(Long organizationId, String type, String externalRef) { }
}
