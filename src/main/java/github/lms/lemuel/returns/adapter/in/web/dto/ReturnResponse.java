package github.lms.lemuel.returns.adapter.in.web.dto;

import github.lms.lemuel.returns.domain.ReturnOrder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReturnResponse {

    private Long id;
    private Long orderId;
    private Long userId;
    private String type;
    private String status;
    private String reason;
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

    public static ReturnResponse from(ReturnOrder returnOrder) {
        return new ReturnResponse(
                returnOrder.getId(),
                returnOrder.getOrderId(),
                returnOrder.getUserId(),
                returnOrder.getType().name(),
                returnOrder.getStatus().name(),
                returnOrder.getReason().name(),
                returnOrder.getReasonDetail(),
                returnOrder.getRefundAmount(),
                returnOrder.getExchangeOrderId(),
                returnOrder.getTrackingNumber(),
                returnOrder.getCarrier(),
                returnOrder.getApprovedAt(),
                returnOrder.getReceivedAt(),
                returnOrder.getCompletedAt(),
                returnOrder.getRejectedAt(),
                returnOrder.getRejectionReason(),
                returnOrder.getCreatedAt(),
                returnOrder.getUpdatedAt()
        );
    }
}
