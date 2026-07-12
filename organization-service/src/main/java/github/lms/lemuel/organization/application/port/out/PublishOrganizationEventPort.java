package github.lms.lemuel.organization.application.port.out;

import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.Organization;

/**
 * 조직 도메인 이벤트 발행 포트 — Transactional Outbox 로 기록되어 도메인 트랜잭션과 원자적으로 커밋된다.
 * shared-common OutboxPublisherScheduler 가 aggregateType="Organization"+eventType 으로 라우팅해 발행한다.
 */
public interface PublishOrganizationEventPort {

    /** organization.created — 조직 생성(생성자 OWNER 자동 등록 포함). */
    void publishCreated(Organization organization, Long ownerUserId);

    /** organization.member_joined — 초대 수락으로 멤버가 ACTIVE 가 됨. */
    void publishMemberJoined(Membership membership);
}
