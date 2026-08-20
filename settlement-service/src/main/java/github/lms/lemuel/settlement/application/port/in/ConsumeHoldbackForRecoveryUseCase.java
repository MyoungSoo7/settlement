package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 지급후 회수분을 정산의 미해제 홀드백에서 소진한다 — settlement 슬라이스의 제공 포트.
 *
 * <p>{@code Settlement.consumeHoldbackForRefund} 는 DONE 정산에서도 허용되는 의도된 예외다
 * (홀드백은 아직 지급되지 않은 유보금이라 확정된 net·즉시지급분을 건드리지 않는다).
 * 그 판단은 정산 도메인의 것이므로, 애그리거트를 열고 저장하고 이벤트를 발행하는 일도 이 슬라이스 안에 둔다.
 */
public interface ConsumeHoldbackForRecoveryUseCase {

    /**
     * 정산을 열어 홀드백에서 {@code recoveredAmount} 까지 소진하고, 소진분이 있으면
     * 저장 + 유보 소진(현금유출) 이벤트 발행까지 수행한다.
     *
     * <p>호출자의 트랜잭션에 합류한다.
     *
     * @return 정산 미발견 또는 셀러 미해석이면 {@link Optional#empty()} — 아무것도 바꾸지 않는다
     */
    Optional<HoldbackConsumption> consumeForRecovery(Long settlementId, Long adjustmentId, BigDecimal recoveredAmount);

    /**
     * @param sellerId payment → order → product 로 해석한 소유 셀러
     * @param consumed 실제로 홀드백에서 깎인 금액(0 이상)
     */
    record HoldbackConsumption(Long sellerId, BigDecimal consumed) {
    }
}
