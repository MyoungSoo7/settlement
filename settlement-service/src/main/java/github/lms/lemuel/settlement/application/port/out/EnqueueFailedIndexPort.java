package github.lms.lemuel.settlement.application.port.out;

/**
 * 인덱싱 실패 시 재시도 큐 추가 Outbound Port
 */
public interface EnqueueFailedIndexPort {

    /**
     * 인덱싱 실패한 정산을 재시도 큐에 추가
     */
    void enqueueForRetry(Long settlementId, String operation);
}
