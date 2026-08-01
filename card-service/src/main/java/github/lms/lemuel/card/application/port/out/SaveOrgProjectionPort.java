package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.OrgRole;

/**
 * 조직·멤버 프로젝션 저장 포트 — 이벤트 재수신에 안전하도록 전부 키 기준 멱등 upsert 다.
 */
public interface SaveOrgProjectionPort {

    void upsertOrg(Long organizationId, String name, String type, String externalRef);

    void upsertMember(Long organizationId, Long userId, OrgRole role);

    /**
     * 멤버 비활성화. 한 번도 적재된 적 없는 멤버라도 비활성 툼스톤을 남겨
     * 순서 역전·재수신에 안전해야 한다.
     */
    void deactivateMember(Long organizationId, Long userId);
}
