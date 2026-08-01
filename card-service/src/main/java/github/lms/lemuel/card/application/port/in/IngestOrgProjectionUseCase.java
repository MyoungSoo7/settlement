package github.lms.lemuel.card.application.port.in;

/**
 * organization-service 이벤트를 로컬 조직·멤버 프로젝션으로 적재하는 유스케이스.
 *
 * <p>card-service 는 organization DB 를 읽을 수 없으므로(DB-per-service), 이 프로젝션이
 * "요청자가 이 조직의 어떤 역할인가"를 판정하는 유일한 근거다. 여기가 낡으면
 * 퇴사자 카드가 유효한 채로 남는다.
 */
public interface IngestOrgProjectionUseCase {

    /**
     * 조직 생성 반영. {@code lemuel.organization.created} 는 소유자의 member_joined 를
     * 따로 발행하지 않으므로(자동 OWNER), 구현은 소유자 멤버십도 함께 적재해야 한다.
     */
    void registerOrg(OrgCommand command);

    /** 멤버 합류·역할 변경 반영 — (organizationId, userId) 키 upsert. */
    void upsertMember(MemberCommand command);

    /** 멤버 제거 반영 — 프로젝션 비활성화. 카드 자동 정지 연쇄는 Task 12 에서 이어진다. */
    void removeMember(long organizationId, long userId);

    record OrgCommand(Long organizationId, String name, String type, String externalRef, Long ownerUserId) { }

    record MemberCommand(Long organizationId, Long userId, String role) { }
}
