package github.lms.lemuel.settlement.adapter.in.internal;

import github.lms.lemuel.recovery.application.port.out.AbsorbSettlementHoldbackPort;
import github.lms.lemuel.settlement.application.port.in.ConsumeHoldbackForRecoveryUseCase;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * recovery 의 {@link AbsorbSettlementHoldbackPort} 를 settlement 유스케이스로 잇는 내부 브리지 어댑터
 * ({@code chargeback.adapter.in.internal.ChargebackSettlementBackfillBridge} 와 동형).
 *
 * <p>결과 타입을 슬라이스마다 따로 두고 여기서 옮겨 담는다 — 두 애플리케이션이 서로의 타입을
 * 직접 참조하지 않게 하는 것이 브리지의 몫이다.
 */
@Component
public class RecoveryHoldbackAbsorptionBridge implements AbsorbSettlementHoldbackPort {

    private final ConsumeHoldbackForRecoveryUseCase useCase;

    public RecoveryHoldbackAbsorptionBridge(ConsumeHoldbackForRecoveryUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Optional<HoldbackAbsorption> absorbForRecovery(Long settlementId, Long adjustmentId,
                                                          BigDecimal recoveredAmount) {
        return useCase.consumeForRecovery(settlementId, adjustmentId, recoveredAmount)
                .map(consumption -> new HoldbackAbsorption(consumption.sellerId(), consumption.consumed()));
    }
}
