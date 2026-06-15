package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.SellerSettlementView;
import github.lms.lemuel.loan.domain.SettlementViewStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class SettlementViewPersistenceAdapter implements SaveSettlementViewPort, LoadSettlementViewPort {

    private final SellerSettlementViewRepository repository;

    public SettlementViewPersistenceAdapter(SellerSettlementViewRepository repository) {
        this.repository = repository;
    }

    @Override
    public void upsert(SellerSettlementView view) {
        // settlementId 가 할당 식별자이므로 save() 는 merge(존재 시 UPDATE / 없으면 INSERT) = 멱등 UPSERT.
        repository.save(new SellerSettlementViewJpaEntity(
                view.getSettlementId(),
                view.getSellerId(),
                view.getAmount(),
                view.getDueDate(),
                view.getStatus(),
                LocalDateTime.now()));
    }

    @Override
    public void markConfirmed(long settlementId) {
        repository.updateStatus(settlementId, SettlementViewStatus.CONFIRMED);
    }

    @Override
    public BigDecimal sumUnpaidBySeller(Long sellerId) {
        return repository.sumBySellerAndStatus(sellerId, SettlementViewStatus.PENDING);
    }

    @Override
    public BigDecimal sumUnpaidBySellerForUpdate(Long sellerId) {
        return repository.findBySellerAndStatusForUpdate(sellerId, SettlementViewStatus.PENDING).stream()
                .map(SellerSettlementViewJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
