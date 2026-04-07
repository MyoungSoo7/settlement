package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Order Persistence Adapter
 */
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements LoadOrderPort, SaveOrderPort {

    private final SpringDataOrderJpaRepository orderJpaRepository;
    private final SpringDataOrderItemJpaRepository orderItemJpaRepository;
    private final OrderPersistenceMapper mapper;

    @Override
    public Optional<Order> findById(Long orderId) {
        return orderJpaRepository.findById(orderId)
                .map(this::toDomainWithItems);
    }

    @Override
    public List<Order> findByUserId(Long userId) {
        return orderJpaRepository.findByUserId(userId)
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    @Override
    public List<Order> findAll() {
        return orderJpaRepository.findAll()
                .stream()
                .map(this::toDomainWithItems)
                .collect(Collectors.toList());
    }

    @Override
    public Order save(Order order) {
        OrderJpaEntity entity = mapper.toEntity(order);
        OrderJpaEntity saved = orderJpaRepository.save(entity);

        // OrderItem 저장
        if (order.getItems() != null && !order.getItems().isEmpty()) {
            for (OrderItem item : order.getItems()) {
                OrderItemJpaEntity itemEntity = mapper.toItemEntity(item);
                itemEntity.setOrderId(saved.getId());
                orderItemJpaRepository.save(itemEntity);
            }
        }

        return toDomainWithItems(saved);
    }

    /**
     * Order JPA Entity를 Domain으로 변환하면서 OrderItem도 함께 로드
     */
    private Order toDomainWithItems(OrderJpaEntity entity) {
        Order order = mapper.toDomain(entity);

        List<OrderItemJpaEntity> itemEntities = orderItemJpaRepository.findByOrderId(entity.getId());
        if (!itemEntities.isEmpty()) {
            List<OrderItem> items = itemEntities.stream()
                    .map(mapper::toItemDomain)
                    .collect(Collectors.toList());
            order.setItems(items);
        }

        return order;
    }
}
