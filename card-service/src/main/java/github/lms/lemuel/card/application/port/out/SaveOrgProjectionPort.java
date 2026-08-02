package github.lms.lemuel.card.application.port.out;

/**
 * 조직·멤버 프로젝션 적재 포트 — Task 7 컨슈머 5종 중 organization 계열 3종이 쓴다.
 *
 * <p>모든 메서드가 멱등 UPSERT(재수신해도 안전)다 — 컨슈머 레벨 멱등(processed_events)과
 * 이중 방어를 이룬다. 여기에 더해 <b>멤버십 세대(membershipId) 비교</b>로 토픽 간 순서 역전을
 * 방어한다: joined/role_changed/removed 는 서로 다른 토픽이라 도착 순서가 보장되지 않는데,
 * REMOVED 는 멤버십 생애주기의 터미널이고 재합류는 항상 새(더 큰) membershipId 를 받으므로,
 * "더 낮은 세대의 이벤트"와 "이미 제거된 같은 세대의 활성화"는 무시하는 것이 정답이다.
 */
public interface SaveOrgProjectionPort {

    /** organization.created(type=SELLER) 수신 시 조직 프로젝션을 멱등 UPSERT 한다. */
    void saveOrg(Long organizationId, String name, String type, String externalRef);

    /**
     * member_joined/member_role_changed 공용 진입점 — "이 조직에 이 사용자가 이 역할로
     * 활성 상태로 존재한다"를 UPSERT 한다. 단, 저장된 세대보다 낮거나, 같은 세대가 이미
     * 제거(active=false)됐다면 <b>부활시키지 않고 무시</b>한다(늦게 도착한 과거 이벤트).
     *
     * @param membershipId 이벤트의 멤버십 id — created 경유 OWNER 등록처럼 없으면 null
     *                     (null 은 세대 비교에서 항상 열위 — 제거 톰스톤을 이기지 못한다)
     */
    void upsertMember(Long organizationId, Long userId, String role, Long membershipId);

    /**
     * member_removed 수신 시 멤버 프로젝션을 <b>비활성화</b>한다(행 삭제 아님 — 이력 보존).
     * 행이 없으면 <b>톰스톤(active=false)을 남긴다</b> — 제거가 합류보다 먼저 도착한 경우,
     * 늦게 온 같은 세대 joined 가 이 톰스톤에 막혀 부활하지 못한다.
     * 저장된 세대가 더 높으면(이미 재합류) 과거 제거 이벤트이므로 무시한다.
     *
     * @param membershipId 제거된 멤버십 id
     */
    void deactivateMember(Long organizationId, Long userId, Long membershipId);
}
