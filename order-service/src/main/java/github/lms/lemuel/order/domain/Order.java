package github.lms.lemuel.order.domain;
import github.lms.lemuel.order.domain.exception.InvalidOrderStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

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
    private final Long userId;
    private final Long productId;
    private final BigDecimal amount;
    private OrderStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal shippingFee = BigDecimal.ZERO;  // 결제에 포함된 배송비(기본 0). 환불 정책 계산에 사용.
    private boolean shipped = false;                   // 배송 시작(IN_TRANSIT/DELIVERED 도달) 여부 — 상태 전이와 무관하게 보존.
    private final List<OrderItem> items = new ArrayList<>();

    // 정본 생성자 — 생성/복원 팩토리(create/createMultiItem/rehydrate)만 통과(Settlement 와 동형).
    // 불변 식별·금액 필드(userId·productId·amount·createdAt)를 여기서 못박아 재할당을 컴파일 단에서 봉인하고,
    // 외부의 임의 status 주입도 함께 봉인한다.
    private Order(Long id, Long userId, Long productId, BigDecimal amount, OrderStatus status,
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
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(null, userId, productId, amount, OrderStatus.CREATED, now, now);
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
            throw new OrderInvariantViolationException("다건 주문은 최소 1 개 이상의 아이템이 필요합니다");
        }
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        if (discount.signum() < 0) {
            throw new OrderInvariantViolationException("할인 금액은 음수일 수 없습니다");
        }
        // 라운딩 정책 경계 — 공용 Money VO(shared-common)를 여기서는 의도적으로 쓰지 않는다.
        // line_amount = unitPrice(scale 0 정수 KRW) × quantity(int) 이고 할인도 정수라 이 합산·차감은
        // 항상 정확한 정수 연산이다: 반올림 여지가 없어 Money 의 scale 2 HALF_UP 정규화 이득이 0 이다.
        // 반대로 Money 를 통과시키면 amount 가 scale 2(예: 3088000.00)로 바뀌어, 이 금액이 흘러가는
        // 결제·정산 프로젝션의 금액 비교(MSA 경계)에 scale drift 만 유발한다. Money javadoc 의
        // "scale 2 HALF_UP 통화 전용" 경계와 일치하는 판단 — 정수 주문 총액은 raw BigDecimal 로 둔다.
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add); // 정수 KRW 정확 합산 — Money 미적용(아래 경계 주석)
        if (discount.compareTo(subtotal) >= 0) {
            throw new OrderInvariantViolationException(
                    "할인 금액(" + discount + ") 이 주문 소계(" + subtotal + ") 이상일 수 없습니다");
        }
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(null, userId, null, subtotal.subtract(discount),
                OrderStatus.CREATED, now, now);
        order.validateUserId();
        order.validateAmount();
        order.items.addAll(items);
        return order;
    }

    // 도메인 규칙: userId 검증
    private void validateUserId() {
        if (userId == null || userId <= 0) {
            throw new OrderInvariantViolationException("User ID must be a positive number");
        }
    }

    // 도메인 규칙: amount 검증
    private void validateAmount() {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderInvariantViolationException("Amount must be greater than zero");
        }
    }

    /**
     * 상태머신 가드 전이. {@link OrderStatus#canTransitionTo(OrderStatus)} 규칙에 어긋나면 예외.
     * 배송·취소·환불 다단계 전이(서비스 계층)와 타 컨텍스트(payment) 의 상태 변경 요청이 모두 이 경로를 거친다.
     * 동일 상태로의 재적용은 멱등 처리(no-op)한다.
     */
    public void transitionTo(OrderStatus target) {
        if (target == null) {
            throw new OrderInvariantViolationException("target status required");
        }
        if (this.status == target) {
            return; // 멱등: 동일 상태 재적용 무시
        }
        if (!this.status.canTransitionTo(target)) {
            throw new InvalidOrderStateException(this.status, target);
        }
        this.status = target;
        // 배송이 한 번이라도 시작되면(IN_TRANSIT/DELIVERED) 기록을 남긴다 — 이후 환불 신청으로
        // 상태가 REFUND_REQUESTED 로 바뀌어도 "배송 시작됨" 사실은 유지되어 환불 정책이 배송비를 차감한다.
        if (target == OrderStatus.IN_TRANSIT || target == OrderStatus.DELIVERED) {
            this.shipped = true;
        }
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 주문 취소 — "결제 전 취소" 의 좁은 의미(CREATED 만).
    // 전이표 canTransitionTo(CANCELED) 는 CANCELLATION_REQUESTED/APPROVED 도 허용하나(취소승인 흐름은
    // 서비스가 transitionTo 로 처리), 이 메서드의 의미는 결제 전 취소로 한정되므로 isCancelable() 로 위임한다.
    public void cancel() {
        if (!isCancelable()) {
            throw new InvalidOrderStateException(this.status, OrderStatus.CANCELED);
        }
        this.status = OrderStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 주문 완료 (결제 완료) — 허용 전이는 전이표 canTransitionTo(PAID) 단일 출처에 위임한다
    // (CREATED 만 PAID 로 전이 가능 — 인라인 가드와 동형).
    public void complete() {
        if (!this.status.canTransitionTo(OrderStatus.PAID)) {
            throw new InvalidOrderStateException(this.status, OrderStatus.PAID);
        }
        this.status = OrderStatus.PAID;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 환불 처리 — "PAID 에서의 단순 환불" 의 좁은 의미(PAID 만).
    // 전이표 canTransitionTo(REFUNDED) 는 배송단계·취소승인에서의 환불도 허용하나(그 경로는 서비스가
    // transitionTo 로 처리), 이 메서드의 의미는 PAID 직접 환불로 한정되므로 isRefundable() 로 위임한다.
    public void refund() {
        if (!isRefundable()) {
            throw new InvalidOrderStateException(this.status, OrderStatus.REFUNDED);
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

    /**
     * 영속 레코드 복원 팩토리. 매퍼가 no-arg + setter 대신 이 경로로만 도메인을 재구성해
     * 상태 전이 규칙을 우회하는 임의 status 주입을 봉인한다. items 는 별도 로드되어 replaceItems 로 부착.
     */
    public static Order rehydrate(Long id, Long userId, Long productId, BigDecimal amount,
                                  OrderStatus status, LocalDateTime createdAt, LocalDateTime updatedAt,
                                  BigDecimal shippingFee, boolean shipped) {
        Order order = new Order(id, userId, productId, amount, status, createdAt, updatedAt);
        order.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        order.shipped = shipped;
        return order;
    }

    /**
     * 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). setter 우회·재부여를 막는다
     * (Settlement#assignId 와 동일 인프라 가드 — 재부여는 프로그래밍 오류라 generic IllegalStateException).
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    /**
     * 결제에 포함된 배송비 확정(null 은 0 으로 방어). 환불 정책 계산의 입력값.
     */
    public void assignShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getShippingFee() {
        return shippingFee;
    }

    public boolean isShipped() {
        return shipped;
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
