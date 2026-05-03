package github.lms.lemuel.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 *
 * <p>두 가지 생성 경로:
 * <ul>
 *   <li>{@link #create(Long, Long, BigDecimal)} — 단건 주문 (레거시 호환). productId 단일.</li>
 *   <li>{@link #createMultiItem(Long, List)} — 다건 주문. productId NULL, items 가 진실의 원천.</li>
 * </ul>
 *
 * <p>amount 는 다건 주문에서 모든 line_amount 의 합으로 자동 계산되어 도메인 불변식
 * (영수증 ↔ 결제 ↔ 정산 금액 일치) 을 보장한다.
 */
public class Order {

    private Long id;
    private Long userId;
    private Long productId;
    private BigDecimal amount;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final List<OrderItem> items = new ArrayList<>();

    // 기본 생성자
    public Order() {
        this.status = OrderStatus.CREATED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Order(Long id, Long userId, Long productId, BigDecimal amount, OrderStatus status,
                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.productId = productId;
        this.amount = amount;
        this.status = status != null ? status : OrderStatus.CREATED;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Order create(Long userId, Long productId, BigDecimal amount) {
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setAmount(amount);
        order.validateUserId();
        order.validateAmount();
        return order;
    }

    public static Order create(Long userId, BigDecimal amount) {
        return create(userId, 1L, amount); // 기본 productId를 1로 지정
    }

    /**
     * 다건 주문 팩토리.
     *
     * <p>amount 는 모든 OrderItem.lineAmount 의 합으로 자동 계산되며,
     * productId 는 null 로 두어 "이 주문은 다건이다" 라는 의미를 부여한다.
     * 외부에서 amount 를 수동 지정할 수 없어 영수증/결제/정산 금액 정합성이 도메인 차원에서 보장된다.
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("다건 주문은 최소 1 개 이상의 아이템이 필요합니다");
        }
        Order order = new Order();
        order.setUserId(userId);
        order.validateUserId();
        BigDecimal total = items.stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setAmount(total);
        order.validateAmount();
        order.items.addAll(items);
        return order;
    }

    // 도메인 규칙: userId 검증
    public void validateUserId() {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be a positive number");
        }
    }

    // 도메인 규칙: amount 검증
    public void validateAmount() {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
    }

    // 비즈니스 메서드: 주문 취소
    public void cancel() {
        if (this.status != OrderStatus.CREATED) {
            throw new IllegalStateException("Only CREATED orders can be canceled");
        }
        this.status = OrderStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 주문 완료 (결제 완료)
    public void complete() {
        if (this.status != OrderStatus.CREATED) {
            throw new IllegalStateException("Only CREATED orders can be completed");
        }
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 환불 처리
    public void refund() {
        if (this.status != OrderStatus.PAID) {
            throw new IllegalStateException("Only PAID orders can be refunded");
        }
        this.status = OrderStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isCancelable() {
        return this.status == OrderStatus.CREATED;
    }

    public boolean isRefundable() {
        return this.status == OrderStatus.PAID;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 다건 주문 라인 아이템 (단건 주문은 빈 리스트).
     */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isMultiItem() {
        return !items.isEmpty();
    }

    /**
     * Persistence 어댑터에서 자식들에 PK 가 부여된 후 부모 id 를 자식에게 주입할 때 사용.
     */
    public void attachItemsToOrder() {
        if (this.id == null) {
            throw new IllegalStateException("Order id 부여 후에만 호출 가능");
        }
        for (OrderItem item : items) {
            item.attachToOrder(this.id);
        }
    }

    /**
     * 영속 상태 복원 시 자식 아이템 채우기.
     */
    public void replaceItems(List<OrderItem> reloadedItems) {
        this.items.clear();
        if (reloadedItems != null) {
            this.items.addAll(reloadedItems);
        }
    }
}
