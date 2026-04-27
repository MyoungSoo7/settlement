package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * Port for external Payment Gateway integration
 */
public interface PgClientPort {
    /**
     * Authorize payment with PG
     * @return PG transaction ID
     */
    String authorize(Long paymentId, BigDecimal amount, String paymentMethod);

    /**
     * Capture authorized payment
     */
    void capture(String pgTransactionId, BigDecimal amount);

    /**
     * Refund captured payment
     */
    void refund(String pgTransactionId, BigDecimal amount);
}
