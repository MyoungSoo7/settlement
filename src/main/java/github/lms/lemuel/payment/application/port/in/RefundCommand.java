package github.lms.lemuel.payment.application.port.in;

import java.math.BigDecimal;

public record RefundCommand(Long paymentId, BigDecimal refundAmount,
                             String idempotencyKey, String reason) {

    public RefundCommand {
        if (paymentId == null) throw new IllegalArgumentException("paymentId required");
        if (refundAmount == null || refundAmount.signum() <= 0)
            throw new IllegalArgumentException("refundAmount must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("idempotencyKey required");
    }
}
