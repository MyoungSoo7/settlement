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
 * Order Persistence Adapter — 다건 주문 (OrderItem) 처리 포함.
 *
 * <p>저장 시: Order 저장 → 부여된 PK 를 자식 OrderItem 에 주입 → 자식들 일괄 저장.
 * 로드 시: Order 도메인 복원 + 자식 OrderItem 들 추가 로딩.
 */
@Repository
@RequiredArgsConstructor
public class OrderPersistenceAdapter implements LoadOrderPort, SaveOrderPort {

    private final SpringDataOrderJpaRepository orderJpaRepository;
    private final SpringDataOrderItemRepository orderItemRepository;
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
    public List<Order> findByUserId(Long userId, String status,
                                    java.time.LocalDateTime from,
                                    java.time.LocalDateTime to) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        return orderJpaRepository.findUserOrders(userId, normalizedStatus, from, to)
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

        // 다건 주문이면 자식 아이템들도 함께 저장
        if (order.isMultiItem()) {
            for (OrderItem item : order.getItems()) {
                OrderItemJpaEntity itemEntity = new OrderItemJpaEntity(
                        item.getId(), saved.getId(), item.getProductId(), item.getVariantId(),
                        item.getSku(), item.getProductName(), item.getUnitPrice(),
                        item.getQuantity(), item.getLineAmount(), item.getCreatedAt()
                );
                orderItemRepository.save(itemEntity);
            }
        }

        Order result = mapper.toDomain(saved);
        if (order.isMultiItem()) {
            // saved Order 에 자식 아이템 다시 로드해서 부착
            List<OrderItem> reloaded = orderItemRepository.findByOrderIdOrderByIdAsc(saved.getId())
                    .stream()
                    .map(OrderPersistenceAdapter::toItemDomain)
                    .toList();
            result.replaceItems(reloaded);
        }
        return result;
    }

    private Order toDomainWithItems(OrderJpaEntity entity) {
        Order order = mapper.toDomain(entity);
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(entity.getId())
                .stream()
                .map(OrderPersistenceAdapter::toItemDomain)
                .toList();
        order.replaceItems(items);
        return order;
    }

    private static OrderItem toItemDomain(OrderItemJpaEntity e) {
        return OrderItem.rehydrate(
                e.getId(), e.getOrderId(), e.getProductId(), e.getVariantId(),
                e.getSku(), e.getProductName(), e.getUnitPrice(),
                e.getQuantity(), e.getLineAmount(), e.getCreatedAt()
        );
    }
}
