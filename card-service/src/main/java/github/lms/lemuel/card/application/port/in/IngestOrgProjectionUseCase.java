package github.lms.lemuel.card.application.port.in;

/**
 * organization-service 이벤트(created/member_joined/member_role_changed/member_removed)를
 * 조직·멤버 프로젝션으로 적재하는 인바운드 포트 — Task 7 컨슈머 4종의 공용 진입점.
 * 이탈({@link #removeMember(Long, Long, Long)})은 프로젝션 비활성화와 카드 정지를 한 트랜잭션으로 잇는다.
 */
public interface IngestOrgProjectionUseCase {

    /**
     * organization.created(type=SELLER) 반영 — 조직 프로젝션 생성 + 생성자를 OWNER 멤버로 등록한다.
     *
     * <p>organization-service 는 조직 생성 시 {@code OrganizationCreated} 이벤트만 발행하고
     * 별도의 {@code member_joined} 를 오너용으로 추가 발행하지 않는다
     * (OrganizationCommandService — publishCreated 만 호출, publishMemberJoined 는 초대 수락 전용).
     * 그래서 이 메서드가 오너 멤버 등록까지 함께 하지 않으면 오너가 영구히 프로젝션에 없는 상태로
     * 남는다 — 스키마 설명에 {@code ownerUserId}가 "생성자(자동 OWNER)"라고 명시된 이유이기도 하다.
     */
    void createOrg(OrgCommand command);

    /**
     * member_joined(신규 합류) 또는 member_role_changed(기존 활성 멤버의 역할 변경) 반영.
     * 역할 변경 이벤트는 {@code newRole} 만 이 커맨드에 담아 호출한다(브리프 리졸루션 #3).
     */
    void upsertMember(MemberCommand command);

    /**
     * member_removed 반영 — 멤버 프로젝션 비활성화 + 이탈자 카드 정지(같은 트랜잭션).
     *
     * @param membershipId 제거된 멤버십의 id — 토픽 간 순서 역전 방어의 기준
     *                     (REMOVED 는 멤버십 생애주기의 터미널이라, 같은 membershipId 의
     *                     joined/role_changed 가 늦게 도착해도 부활시키면 안 된다)
     */
    void removeMember(Long organizationId, Long userId, Long membershipId);

    record OrgCommand(Long organizationId, String name, String type, String externalRef) {
    }

    /**
     * @param membershipId 이벤트가 속한 멤버십 id — 재합류는 새(더 큰) membershipId 를 가진다.
     *                     organization.created 경유 OWNER 등록처럼 멤버십 id 가 없는 경로는 null.
     */
    record MemberCommand(Long organizationId, Long userId, String role, Long membershipId) {
    }
}
