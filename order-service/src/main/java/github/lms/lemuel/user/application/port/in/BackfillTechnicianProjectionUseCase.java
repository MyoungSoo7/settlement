package github.lms.lemuel.user.application.port.in;

/**
 * reservation-service 기사 프로젝션(technician_view) 백필.
 *
 * <p>DB 분리 컷오버 직후 reservation-service 의 technician_view 가 비어 있으면 모든 기사 배정이
 * 실패한다. 기존 TECHNICIAN 회원에 대해 UserMembershipChanged 이벤트를 일괄 재발행해
 * 프로젝션을 시드한다(멱등 — 재실행 안전).
 */
public interface BackfillTechnicianProjectionUseCase {

    /** TECHNICIAN 회원 전원에 대해 멤버십 이벤트를 재발행한다. 발행 건수를 반환. */
    int backfillTechnicians();
}
