package github.lms.lemuel.settlement.adapter.in.internal;

import github.lms.lemuel.payout.application.port.out.RedriveSettlementDeductionPort;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import org.springframework.stereotype.Component;

/**
 * payout 의 {@link RedriveSettlementDeductionPort} 를 settlement 유스케이스로 잇는 내부 브리지 어댑터
 * ({@code chargeback.adapter.in.internal.ChargebackSettlementBackfillBridge} 와 동형).
 *
 * <p>어댑터는 양쪽 애플리케이션 포트를 볼 수 있으므로, 두 슬라이스의 애플리케이션이 서로를 직접
 * import 하지 않게 하는 경계 유지 장치가 된다.
 *
 * <p>트랜잭션은 이 클래스에 걸지 않는다 — 위임 대상인 {@link ApplyLoanDeductionUseCase} 구현이
 * 자기 트랜잭션을 갖고 있고, 브리지가 프록시 앞에 한 겹 끼어도 그 경계는 그대로다.
 */
@Component
public class PayoutDeductionRedriveBridge implements RedriveSettlementDeductionPort {

    private final ApplyLoanDeductionUseCase useCase;

    public PayoutDeductionRedriveBridge(ApplyLoanDeductionUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public boolean redriveFromRecordedDeduction(long settlementId, long sellerId) {
        return useCase.redriveFromRecordedDeduction(settlementId, sellerId);
    }
}
