package github.lms.lemuel.payment.adapter.in.dto;

import github.lms.lemuel.payment.domain.Refund;

import java.math.BigDecimal;

public record RefundResponse(Long refundId, Long paymentId, BigDecimal amount,
                              String status, String idempotencyKey) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmount(),
                r.getStatus().name(), r.getIdempotencyKey());
    }
}
