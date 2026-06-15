package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.SellerSettlementView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SettlementCreated 이벤트를 로컬 정산 뷰로 적재한다.
 * settlementId 가 식별자이므로 재수신 시 멱등 UPSERT 된다(컨슈머 측 processed_events 와 이중 방어).
 */
@Service
public class IngestSettlementService implements IngestSettlementUseCase {

    private final SaveSettlementViewPort saveSettlementViewPort;

    public IngestSettlementService(SaveSettlementViewPort saveSettlementViewPort) {
        this.saveSettlementViewPort = saveSettlementViewPort;
    }

    @Override
    @Transactional
    public void ingest(IngestSettlementCommand command) {
        SellerSettlementView view = SellerSettlementView.pending(
                command.settlementId(),
                command.sellerId(),
                command.amount(),
                command.dueDate());
        saveSettlementViewPort.upsert(view);
    }
}
