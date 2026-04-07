package github.lms.lemuel.shipping.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 배송 도메인 엔티티 (순수 POJO, 프레임워크 의존성 없음)
 */
public class Delivery {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("50000");
    private static final BigDecimal DEFAULT_SHIPPING_FEE = new BigDecimal("3000");

    private Long id;
    private Long orderId;
    private Long addressId;
    private DeliveryStatus status;
    private String trackingNumber;
    private String carrier;
    private String recipientName;
    private String phone;
    private String address;
    private BigDecimal shippingFee;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Delivery() {}

    public static Delivery create(Long orderId, Long addressId, String recipientName,
                                  String phone, String address, BigDecimal shippingFee) {
        Delivery d = new Delivery();
        d.orderId       = orderId;
        d.addressId     = addressId;
        d.recipientName = recipientName;
        d.phone         = phone;
        d.address       = address;
        d.shippingFee   = shippingFee;
        d.status        = DeliveryStatus.PREPARING;
        d.createdAt     = LocalDateTime.now();
        d.updatedAt     = LocalDateTime.now();
        return d;
    }

    public void ship(String trackingNumber, String carrier) {
        validateTransition(DeliveryStatus.SHIPPED);
        this.trackingNumber = trackingNumber;
        this.carrier        = carrier;
        this.status         = DeliveryStatus.SHIPPED;
        this.shippedAt      = LocalDateTime.now();
        this.updatedAt      = LocalDateTime.now();
    }

    public void startTransit() {
        validateTransition(DeliveryStatus.IN_TRANSIT);
        this.status    = DeliveryStatus.IN_TRANSIT;
        this.updatedAt = LocalDateTime.now();
    }

    public void outForDelivery() {
        validateTransition(DeliveryStatus.OUT_FOR_DELIVERY);
        this.status    = DeliveryStatus.OUT_FOR_DELIVERY;
        this.updatedAt = LocalDateTime.now();
    }

    public void deliver() {
        validateTransition(DeliveryStatus.DELIVERED);
        this.status      = DeliveryStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status == DeliveryStatus.DELIVERED) {
            throw new IllegalStateException("이미 배송 완료된 건은 취소할 수 없습니다.");
        }
        this.status    = DeliveryStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    public static BigDecimal calculateShippingFee(BigDecimal orderAmount) {
        if (orderAmount != null && orderAmount.compareTo(FREE_SHIPPING_THRESHOLD) >= 0) {
            return BigDecimal.ZERO;
        }
        return DEFAULT_SHIPPING_FEE;
    }

    private void validateTransition(DeliveryStatus target) {
        boolean valid = switch (target) {
            case SHIPPED          -> status == DeliveryStatus.PREPARING;
            case IN_TRANSIT       -> status == DeliveryStatus.SHIPPED;
            case OUT_FOR_DELIVERY -> status == DeliveryStatus.IN_TRANSIT;
            case DELIVERED        -> status == DeliveryStatus.OUT_FOR_DELIVERY;
            default               -> false;
        };
        if (!valid) {
            throw new IllegalStateException(
                    String.format("배송 상태를 %s에서 %s로 변경할 수 없습니다.", status, target));
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────────

    public Long getId()                              { return id; }
    public void setId(Long id)                       { this.id = id; }

    public Long getOrderId()                         { return orderId; }
    public void setOrderId(Long orderId)             { this.orderId = orderId; }

    public Long getAddressId()                       { return addressId; }
    public void setAddressId(Long addressId)         { this.addressId = addressId; }

    public DeliveryStatus getStatus()                { return status; }
    public void setStatus(DeliveryStatus status)     { this.status = status; }

    public String getTrackingNumber()                { return trackingNumber; }
    public void setTrackingNumber(String v)          { this.trackingNumber = v; }

    public String getCarrier()                       { return carrier; }
    public void setCarrier(String carrier)           { this.carrier = carrier; }

    public String getRecipientName()                 { return recipientName; }
    public void setRecipientName(String v)           { this.recipientName = v; }

    public String getPhone()                         { return phone; }
    public void setPhone(String phone)               { this.phone = phone; }

    public String getAddress()                       { return address; }
    public void setAddress(String address)            { this.address = address; }

    public BigDecimal getShippingFee()               { return shippingFee; }
    public void setShippingFee(BigDecimal v)         { this.shippingFee = v; }

    public LocalDateTime getShippedAt()              { return shippedAt; }
    public void setShippedAt(LocalDateTime t)        { this.shippedAt = t; }

    public LocalDateTime getDeliveredAt()            { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime t)      { this.deliveredAt = t; }

    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void setCreatedAt(LocalDateTime t)        { this.createdAt = t; }

    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t)        { this.updatedAt = t; }
}
