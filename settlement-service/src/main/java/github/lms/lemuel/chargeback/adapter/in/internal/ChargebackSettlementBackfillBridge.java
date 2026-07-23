package github.lms.lemuel.chargeback.adapter.in.internal;

import github.lms.lemuel.chargeback.application.port.in.BackfillChargebackSettlementUseCase;
import github.lms.lemuel.settlement.application.port.out.BackfillChargebackSettlementLinkPort;
import org.springframework.stereotype.Component;

/**
 * settlement 컨텍스트의 {@link BackfillChargebackSettlementLinkPort} 를 chargeback 유스케이스로
 * 잇는 내부 브리지 어댑터 — settlement 애플리케이션이 chargeback 애플리케이션을 직접 import 하지
 * 않게 하는 컨텍스트 경계 유지 장치(어댑터는 양쪽 애플리케이션 포트를 볼 수 있다).
 */
@Component
public class ChargebackSettlementBackfillBridge implements BackfillChargebackSettlementLinkPort {

    private final BackfillChargebackSettlementUseCase useCase;

    public ChargebackSettlementBackfillBridge(BackfillChargebackSettlementUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public int backfillChargebacks(Long paymentId, Long settlementId) {
        return useCase.backfillSettlementLink(paymentId, settlementId);
    }
}
