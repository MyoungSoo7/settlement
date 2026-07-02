package github.lms.lemuel.order.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상태 Enum + 허용 전이(상태머신) 정의.
 *
 * <p>전이 규칙을 도메인에 명시해 임의 전이(예: CREATED → DELIVERED, REFUNDED → PAID)를 차단한다.
 * 실제 전이는 {@link Order#transitionTo(OrderStatus)} 가 이 규칙으로 검증한다.
 *
 * <p>환불(REFUNDED)은 결제 이후 어떤 진행 단계(배송 포함)에서도 발생할 수 있어 종단 도달을 관대하게 허용하되,
 * 결제 전(CREATED)이나 종단 상태(CANCELED/REFUNDED/REFUND_COMPLETED)에서의 비정상 전이는 막는다.
 */
public enum OrderStatus {
    CREATED,    // 주문 생성됨(결제 전)
    PAID,       // 결제 완료로 주문 확정
    SHIPPING_PENDING,
    IN_TRANSIT,
    DELIVERED,
    CANCELLATION_REQUESTED,
    CANCELLATION_APPROVED,
    REFUND_REQUESTED,
    REFUND_COMPLETED,
    CANCELED,   // 결제 전 취소 / 취소 승인 종단
    REFUNDED;   // 결제 후 환불 완료 종단

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(PAID, CANCELED, CANCELLATION_REQUESTED));
        ALLOWED.put(PAID, EnumSet.of(SHIPPING_PENDING, REFUND_REQUESTED, REFUNDED, CANCELLATION_REQUESTED));
        ALLOWED.put(SHIPPING_PENDING, EnumSet.of(IN_TRANSIT, REFUND_REQUESTED, REFUNDED));
        ALLOWED.put(IN_TRANSIT, EnumSet.of(DELIVERED, REFUND_REQUESTED, REFUNDED));
        ALLOWED.put(DELIVERED, EnumSet.of(REFUND_REQUESTED, REFUNDED));
        ALLOWED.put(CANCELLATION_REQUESTED, EnumSet.of(CANCELLATION_APPROVED, CANCELED));
        ALLOWED.put(CANCELLATION_APPROVED, EnumSet.of(CANCELED, REFUND_REQUESTED, REFUNDED));
        ALLOWED.put(REFUND_REQUESTED, EnumSet.of(REFUND_COMPLETED, REFUNDED));
        // 종단 상태 — 추가 전이 없음
        ALLOWED.put(REFUND_COMPLETED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(OrderStatus.class));
    }

    /** 현재 상태에서 {@code target} 으로 전이 가능한지. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** 더 이상 전이가 없는 종단 상태인지. */
    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Collections.emptySet()).isEmpty();
    }

    public static OrderStatus fromString(String status) {
        try {
            return OrderStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return CREATED; // 기본값
        }
    }
}
