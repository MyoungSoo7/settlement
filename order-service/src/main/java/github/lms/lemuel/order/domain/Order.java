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
 * <p>amount 는 다건 주문에서 {@code (모든 line_amount 의 합) - 쿠폰 할인} 으로 자동 계산되어
 * 도메인 불변식 (영수증 ↔ 결제 ↔ 정산 금액 일치) 을 보장한다. 쿠폰 없는 주문은 할인 0 이므로
 * amount = line_amount 합.
 */
public class Order {

    private Long id;
    private Long userId;
    private Long productId;
    private BigDecimal amount;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal shippingFee = BigDecimal.ZERO;  // 결제에 포함된 배송비(기본 0). 환불 정책 계산에 사용.
    private boolean shipped = false;                   // 배송 시작(IN_TRANSIT/DELIVERED 도달) 여부 — 상태 전이와 무관하게 보존.
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
        return createMultiItem(userId, items, BigDecimal.ZERO);
    }

    /**
     * 다건 주문 팩토리 (쿠폰 할인 반영).
     *
     * <p>소계(subtotal) = 모든 {@link OrderItem#getLineAmount()} 의 합. 최종 결제 금액
     * {@code amount = subtotal - discountAmount} 로, 외부에서 amount 를 임의 지정할 수 없어
     * 영수증/결제/정산 정합성이 도메인 차원에서 보장된다.
     *
     * <p>할인 금액은 별도 컬럼으로 저장하지 않아도 subtotal(= 영속된 {@code order_items.line_amount}
     * 합) 에서 {@code discount = subtotal - amount} 로 항상 역산할 수 있으므로 스키마 확장이 필요 없다.
     * 쿠폰-주문의 연결 자체는 {@code coupon_usages.order_id} 가 보존한다.
     *
     * @param discountAmount 쿠폰 할인 금액(없으면 {@code null}/0). 0 이상이어야 하고, 결제 금액은
     *                       0 보다 커야 하므로 subtotal 미만이어야 한다.
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items, BigDecimal discountAmount) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("다건 주문은 최소 1 개 이상의 아이템이 필요합니다");
        }
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        if (discount.signum() < 0) {
            throw new IllegalArgumentException("할인 금액은 음수일 수 없습니다");
        }
        Order order = new Order();
        order.setUserId(userId);
        order.validateUserId();
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (discount.compareTo(subtotal) >= 0) {
            throw new IllegalArgumentException(
                    "할인 금액(" + discount + ") 이 주문 소계(" + subtotal + ") 이상일 수 없습니다");
        }
        order.setAmount(subtotal.subtract(discount));
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

    /**
     * 상태머신 가드 전이. {@link OrderStatus#canTransitionTo(OrderStatus)} 규칙에 어긋나면 예외.
     * 배송·취소·환불 다단계 전이(서비스 계층)와 타 컨텍스트(payment) 의 상태 변경 요청이 모두 이 경로를 거친다.
     * 동일 상태로의 재적용은 멱등 처리(no-op)한다.
     */
    public void transitionTo(OrderStatus target) {
        if (target == null) {
            throw new IllegalArgumentException("target status required");
        }
        if (this.status == target) {
            return; // 멱등: 동일 상태 재적용 무시
        }
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "허용되지 않은 주문 상태 전이: " + this.status + " → " + target);
        }
        this.status = target;
        // 배송이 한 번이라도 시작되면(IN_TRANSIT/DELIVERED) 기록을 남긴다 — 이후 환불 신청으로
        // 상태가 REFUND_REQUESTED 로 바뀌어도 "배송 시작됨" 사실은 유지되어 환불 정책이 배송비를 차감한다.
        if (target == OrderStatus.IN_TRANSIT || target == OrderStatus.DELIVERED) {
            this.shipped = true;
        }
        this.updatedAt = LocalDateTime.now();
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

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }

    public boolean isShipped() {
        return shipped;
    }

    public void setShipped(boolean shipped) {
        this.shipped = shipped;
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
