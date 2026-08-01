package github.lms.lemuel.card.application.port.out;

/**
 * 조직·멤버 프로젝션 적재 포트 — Task 7 컨슈머 5종 중 organization 계열 3종이 쓴다.
 *
 * <p>모든 메서드가 멱등 UPSERT(재수신해도 안전)다 — 컨슈머 레벨 멱등(processed_events)과
 * 이중 방어를 이룬다.
 */
public interface SaveOrgProjectionPort {

    /** organization.created(type=SELLER) 수신 시 조직 프로젝션을 멱등 UPSERT 한다. */
    void saveOrg(Long organizationId, String name, String type, String externalRef);

    /**
     * member_joined/member_role_changed 공용 진입점 — "이 조직에 이 사용자가 이 역할로
     * 활성 상태로 존재한다"를 멱등 UPSERT 한다(active=true 로 강제).
     */
    void upsertMember(Long organizationId, Long userId, String role);

    /**
     * member_removed 수신 시 멤버 프로젝션을 <b>비활성화</b>한다(행 삭제 아님 — 이력 보존).
     * 카드 정지는 Task 12 의 몫 — 이 메서드는 권한 판정용 상태만 갱신한다.
     * 존재하지 않는 멤버면 무해한 no-op(방어적 멱등).
     */
    void deactivateMember(Long organizationId, Long userId);
}
