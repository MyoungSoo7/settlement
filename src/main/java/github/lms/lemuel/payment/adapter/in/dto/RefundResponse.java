package github.lms.lemuel.payment.adapter.in.dto;

import github.lms.lemuel.payment.domain.Refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundResponse(
        Long id,
        Long paymentId,
        BigDecimal amount,
        String reason,
        String status,
        String idempotencyKey,
        LocalDateTime createdAt) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(
                r.getId(),
                r.getPaymentId(),
                r.getAmount(),
                r.getReason(),
                r.getStatus().name(),
                r.getIdempotencyKey(),
                r.getCreatedAt());
    }
}
