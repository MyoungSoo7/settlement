package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase;
import github.lms.lemuel.investment.application.port.out.SaveFundingViewPort;
import github.lms.lemuel.investment.domain.SellerFundingView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * settlement.confirmed 이벤트를 재원 프로젝션으로 적재한다.
 * settlementId 가 식별자이므로 재수신 시 멱등 UPSERT(컨슈머 측 processed_events 와 이중 방어).
 */
@Service
public class IngestConfirmedSettlementService implements IngestConfirmedSettlementUseCase {

    private final SaveFundingViewPort saveFundingViewPort;

    public IngestConfirmedSettlementService(SaveFundingViewPort saveFundingViewPort) {
        this.saveFundingViewPort = saveFundingViewPort;
    }

    @Override
    @Transactional
    public void ingest(IngestConfirmedSettlementCommand command) {
        SellerFundingView view = SellerFundingView.confirmed(
                command.settlementId(),
                command.sellerId(),
                command.amount());
        saveFundingViewPort.upsert(view);
    }
}
