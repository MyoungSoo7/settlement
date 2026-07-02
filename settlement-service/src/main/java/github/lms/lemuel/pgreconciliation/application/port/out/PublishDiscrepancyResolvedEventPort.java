package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;

/**
 * PG 대사 차이 해소(승인) 시 후속 보정 흐름을 트리거하기 위한 이벤트 발행 포트.
 *
 * <p>승인 트랜잭션과 같은 트랜잭션에서 Transactional Outbox 에 적재되어 원자성이 보장되고,
 * shared-common 의 OutboxPublisherScheduler 가 Kafka(lemuel.pgreconciliation.discrepancy_approved)로 발행한다.
 */
public interface PublishDiscrepancyResolvedEventPort {

    /**
     * 차이가 운영자에 의해 승인됐음을 알린다. 페이로드에 보정에 필요한 컨텍스트
     * (paymentId, type, difference 등)를 담아, 후속 보정 핸들러가 타입별 규칙으로 정산을 조정한다.
     */
    void publishDiscrepancyApproved(ReconciliationDiscrepancy discrepancy);
}
