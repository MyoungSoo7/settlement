package github.lms.lemuel.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Order Domain Entity (순수 POJO, 스프링/JPA 의존성 없음)
 * DB 스키마: id, user_id, amount, status, created_at, updated_at
 */
public class Order {

    private Long id;
    private Long userId;
    private Long productId;
    private BigDecimal amount;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Multi-item 관련 필드
    private List<OrderItem> items = new ArrayList<>();
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private Long shippingAddressId;
    private String couponCode;

    // 기본 생성자
    public Order() {
        this.status = OrderStatus.CREATED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.shippingFee = BigDecimal.ZERO;
        this.discountAmount = BigDecimal.ZERO;
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
        this.shippingFee = BigDecimal.ZERO;
        this.discountAmount = BigDecimal.ZERO;
    }

    // 정적 팩토리 메서드 (단일 상품 - 기존 호환)
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
     * 복수 상품 주문 팩토리 메서드
     */
    public static Order createMultiItem(Long userId, List<OrderItem> items,
                                        BigDecimal shippingFee, BigDecimal discountAmount) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }

        Order order = new Order();
        order.setUserId(userId);
        order.items = new ArrayList<>(items);
        order.shippingFee = shippingFee != null ? shippingFee : BigDecimal.ZERO;
        order.discountAmount = discountAmount != null ? discountAmount : BigDecimal.ZERO;

        // 첫 번째 아이템의 productId를 기존 호환용으로 설정
        order.setProductId(items.get(0).getProductId());

        // totalAmount 계산
        order.totalAmount = order.calculateTotalAmount();
        // 기존 호환: amount = totalAmount
        order.setAmount(order.totalAmount);

        order.validateUserId();
        order.validateAmount();
        return order;
    }

    /**
     * 아이템 기반 총액 재계산
     */
    public BigDecimal calculateTotalAmount() {
        if (items == null || items.isEmpty()) {
            return amount != null ? amount : BigDecimal.ZERO;
        }
        BigDecimal itemsTotal = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fee = shippingFee != null ? shippingFee : BigDecimal.ZERO;
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;

        return itemsTotal.add(fee).subtract(discount);
    }

    /**
     * 주문 아이템 수 반환
     */
    public int getItemCount() {
        return items != null ? items.size() : 0;
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

    // Multi-item Getters and Setters
    public List<OrderItem> getItems() {
        return items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }

    public void setItems(List<OrderItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Long getShippingAddressId() {
        return shippingAddressId;
    }

    public void setShippingAddressId(Long shippingAddressId) {
        this.shippingAddressId = shippingAddressId;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public void setCouponCode(String couponCode) {
        this.couponCode = couponCode;
    }
}
