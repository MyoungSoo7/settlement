package github.lms.lemuel.chargeback.application.port.in;

/**
 * 정산 생성 시 사전분쟁(정산 미연결 chargeback) 백필 유스케이스.
 *
 * <p>분쟁은 정산보다 먼저 생길 수 있다(결제 직후 카드사 통지). 그 상태에서 ACCEPT 된 분쟁은
 * settlementId 가 없어 환수 조정(settlement_adjustments)이 만들어지지 못한다. 정산이 생성되는
 * 시점에 같은 결제의 미연결 분쟁을 연결하고, 이미 ACCEPTED 인 건은 환수 조정을 지금 만든다.
 */
public interface BackfillChargebackSettlementUseCase {

    /**
     * 해당 결제의 정산 미연결 분쟁을 {@code settlementId} 로 연결하고,
     * ACCEPTED 분쟁은 환수 조정을 생성한다.
     *
     * @return 연결된 분쟁 수 (0 이면 백필 대상 없음)
     */
    int backfillSettlementLink(Long paymentId, Long settlementId);
}
