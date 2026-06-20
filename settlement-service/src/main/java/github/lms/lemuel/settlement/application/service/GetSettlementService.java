package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 정산 조회 서비스
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetSettlementService implements GetSettlementUseCase {

    private final LoadSettlementPort loadSettlementPort;

    @Override
    public Settlement getSettlementById(Long settlementId) {
        return loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(settlementId));
    }

    @Override
    public List<Settlement> getSettlementsByPaymentId(Long paymentId) {
        return loadSettlementPort.findByPaymentId(paymentId)
                .map(List::of)
                .orElse(List.of());
    }
}
