package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.GenerateSettlementPdfUseCase;
import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.application.port.out.SettlementPdfPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GenerateSettlementPdfService implements GenerateSettlementPdfUseCase {

    private final GetSettlementUseCase getSettlementUseCase;
    private final SettlementPdfPort settlementPdfPort;

    @Override
    public byte[] generate(Long settlementId) {
        Settlement settlement = getSettlementUseCase.getSettlementById(settlementId);
        return settlementPdfPort.render(settlement);
    }
}