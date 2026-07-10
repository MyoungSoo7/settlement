package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.application.port.out.SaveInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class InvestmentOrderPersistenceAdapter implements SaveInvestmentOrderPort, LoadInvestmentOrderPort {

    private final InvestmentOrderRepository repository;

    public InvestmentOrderPersistenceAdapter(InvestmentOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public InvestmentOrder save(InvestmentOrder order) {
        return toDomain(repository.save(toEntity(order)));
    }

    @Override
    public InvestmentOrder load(long orderId) {
        return repository.findById(orderId)
                .map(InvestmentOrderPersistenceAdapter::toDomain)
                .orElseThrow(() -> new InvestmentNotFoundException("투자 주문을 찾을 수 없습니다. orderId=" + orderId));
    }

    @Override
    public List<InvestmentOrder> findBySeller(long sellerId) {
        return repository.findBySellerIdOrderByIdAsc(sellerId).stream()
                .map(InvestmentOrderPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public BigDecimal sumExecutedAmountBySeller(long sellerId) {
        return repository.sumBySellerAndStatus(sellerId, InvestmentOrderStatus.EXECUTED);
    }

    private static InvestmentOrderJpaEntity toEntity(InvestmentOrder order) {
        return new InvestmentOrderJpaEntity(
                order.getId(),
                order.getSellerId(),
                order.getStockCode(),
                order.getAmount(),
                order.getScoreAtOrder(),
                order.getGradeAtOrder(),
                order.getStatus(),
                order.getCreatedAt() == null ? LocalDateTime.now() : order.getCreatedAt());
    }

    private static InvestmentOrder toDomain(InvestmentOrderJpaEntity e) {
        return InvestmentOrder.reconstitute(
                e.getId(), e.getSellerId(), e.getStockCode(), e.getAmount(),
                e.getScoreAtOrder(), e.getGradeAtOrder(), e.getStatus(), e.getCreatedAt());
    }
}
