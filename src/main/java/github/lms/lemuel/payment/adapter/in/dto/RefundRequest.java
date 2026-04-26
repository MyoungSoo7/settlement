package github.lms.lemuel.payment.adapter.in.dto;

import java.math.BigDecimal;

public record RefundRequest(BigDecimal amount, String reason) {
}
