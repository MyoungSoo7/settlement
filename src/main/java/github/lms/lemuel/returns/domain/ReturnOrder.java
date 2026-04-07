package github.lms.lemuel.returns.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 반품/교환 도메인 엔티티 (순수 POJO, 스프링/JPA 의존성 없음)
 */
public class ReturnOrder {

    private Long id;
    private Long orderId;
    private Long userId;
    private ReturnType type;
    private ReturnStatus status;
    private ReturnReason reason;
    private String reasonDetail;
    private BigDecimal refundAmount;
    private Long exchangeOrderId;
    private String trackingNumber;
    private String carrier;
    private LocalDateTime approvedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime completedAt;
    private LocalDateTime rejectedAt;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 기본 생성자
    public ReturnOrder() {
        this.status = ReturnStatus.REQUESTED;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 전체 생성자
    public ReturnOrder(Long id, Long orderId, Long userId, ReturnType type, ReturnStatus status,
                       ReturnReason reason, String reasonDetail, BigDecimal refundAmount,
                       Long exchangeOrderId, String trackingNumber, String carrier,
                       LocalDateTime approvedAt, LocalDateTime receivedAt, LocalDateTime completedAt,
                       LocalDateTime rejectedAt, String rejectionReason,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.status = status != null ? status : ReturnStatus.REQUESTED;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.refundAmount = refundAmount;
        this.exchangeOrderId = exchangeOrderId;
        this.trackingNumber = trackingNumber;
        this.carrier = carrier;
        this.approvedAt = approvedAt;
        this.receivedAt = receivedAt;
        this.completedAt = completedAt;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static ReturnOrder create(Long orderId, Long userId, ReturnType type,
                                     ReturnReason reason, String reasonDetail,
                                     BigDecimal refundAmount) {
        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setOrderId(orderId);
        returnOrder.setUserId(userId);
        returnOrder.setType(type);
        returnOrder.setReason(reason);
        returnOrder.setReasonDetail(reasonDetail);
        returnOrder.setRefundAmount(refundAmount);
        returnOrder.validateOrderId();
        returnOrder.validateUserId();
        return returnOrder;
    }

    // 도메인 검증
    public void validateOrderId() {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be a positive number");
        }
    }

    public void validateUserId() {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be a positive number");
        }
    }

    // 비즈니스 메서드: 승인
    public void approve() {
        if (this.status != ReturnStatus.REQUESTED) {
            throw new IllegalStateException("Only REQUESTED returns can be approved");
        }
        this.status = ReturnStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 거절
    public void reject(String reason) {
        if (this.status != ReturnStatus.REQUESTED) {
            throw new IllegalStateException("Only REQUESTED returns can be rejected");
        }
        this.status = ReturnStatus.REJECTED;
        this.rejectedAt = LocalDateTime.now();
        this.rejectionReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 반송 발송
    public void ship(String trackingNumber, String carrier) {
        if (this.status != ReturnStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED returns can be shipped");
        }
        this.status = ReturnStatus.SHIPPED;
        this.trackingNumber = trackingNumber;
        this.carrier = carrier;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 반송 수령
    public void receive() {
        if (this.status != ReturnStatus.SHIPPED) {
            throw new IllegalStateException("Only SHIPPED returns can be received");
        }
        this.status = ReturnStatus.RECEIVED;
        this.receivedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 완료 (RETURN 타입)
    public void complete() {
        if (this.status != ReturnStatus.RECEIVED) {
            throw new IllegalStateException("Only RECEIVED returns can be completed");
        }
        this.status = ReturnStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 완료 (EXCHANGE 타입 - 교환 주문 ID 설정)
    public void complete(Long exchangeOrderId) {
        if (this.status != ReturnStatus.RECEIVED) {
            throw new IllegalStateException("Only RECEIVED returns can be completed");
        }
        this.status = ReturnStatus.COMPLETED;
        this.exchangeOrderId = exchangeOrderId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 취소
    public void cancel() {
        if (this.status == ReturnStatus.COMPLETED) {
            throw new IllegalStateException("COMPLETED returns cannot be canceled");
        }
        this.status = ReturnStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // 유형 확인
    public boolean isReturnType() {
        return this.type == ReturnType.RETURN;
    }

    public boolean isExchangeType() {
        return this.type == ReturnType.EXCHANGE;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ReturnType getType() {
        return type;
    }

    public void setType(ReturnType type) {
        this.type = type;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public void setStatus(ReturnStatus status) {
        this.status = status;
    }

    public ReturnReason getReason() {
        return reason;
    }

    public void setReason(ReturnReason reason) {
        this.reason = reason;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public void setReasonDetail(String reasonDetail) {
        this.reasonDetail = reasonDetail;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public Long getExchangeOrderId() {
        return exchangeOrderId;
    }

    public void setExchangeOrderId(Long exchangeOrderId) {
        this.exchangeOrderId = exchangeOrderId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(LocalDateTime rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
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
}
