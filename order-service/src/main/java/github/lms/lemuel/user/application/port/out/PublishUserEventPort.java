package github.lms.lemuel.user.application.port.out;

/**
 * user 도메인 이벤트 발행 아웃바운드 포트.
 *
 * <p>회원 멤버십/역할 변경을 outbox 로 발행해, reservation-service 등 구독 서비스가
 * 로컬 프로젝션(예: technician_view)을 동기화하게 한다 (Event-Carried State Transfer).
 */
public interface PublishUserEventPort {

    /** 회원 멤버십/역할/활성 상태 변경 발행. */
    void publishMembershipChanged(Long userId, String role, String membershipStatus, boolean active);
}
