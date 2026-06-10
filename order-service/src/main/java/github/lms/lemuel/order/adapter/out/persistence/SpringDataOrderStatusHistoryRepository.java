package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOrderStatusHistoryRepository
        extends JpaRepository<OrderStatusHistoryJpaEntity, Long> {
}
