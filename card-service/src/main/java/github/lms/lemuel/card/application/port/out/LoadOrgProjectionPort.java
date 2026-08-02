package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.OrgRole;

import java.util.Optional;

/**
 * 조직·멤버 프로젝션 조회 포트 — organization-service 이벤트로 적재된 읽기 전용 복제본을 읽는다.
 *
 * <p>Task 9(발급 심사)·Task 10(발급/한도변경)·Task 12(카드 정지)가 "이 요청자가 이 조직에서
 * 어떤 역할인가"를 판정할 때 쓴다. 프로젝션이 낡으면(=이벤트 소비 지연·유실) 권한 판정이
 * 잘못될 수 있다는 것이 이 포트의 핵심 리스크다.
 */
public interface LoadOrgProjectionPort {

    /** 조직 프로젝션 조회 — 없으면(아직 이벤트 미도착) 빈 Optional. */
    Optional<OrgView> findOrg(Long organizationId);

    /**
     * 이 조직에서 이 사용자의 현재 역할 — <b>활성 멤버만</b> 반환한다
     * ({@code org_member_projection.active = true}).
     * 조직에서 제거된(비활성) 멤버는 빈 Optional — 권한 판정에서 "권한 없음"과 동일하게 취급돼야 한다.
     */
    Optional<OrgRole> findMemberRole(Long organizationId, Long userId);

    /** organization-service {@code Organization} 의 읽기 전용 투영. */
    record OrgView(Long organizationId, String type, String externalRef) {
    }
}
