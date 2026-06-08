package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.SaveOrderStatusHistoryPort;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusHistoryPersistenceAdapter implements SaveOrderStatusHistoryPort {

    private final SpringDataOrderStatusHistoryRepository repository;

    public OrderStatusHistoryPersistenceAdapter(SpringDataOrderStatusHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Long orderId, String previousStatus, String newStatus, String changedBy, String reason) {
        repository.save(new OrderStatusHistoryJpaEntity(orderId, previousStatus, newStatus, changedBy, reason));
    }
}
