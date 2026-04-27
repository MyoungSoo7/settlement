package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.PgClientPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock Payment Gateway adapter (replace with real implementation)
 */
@Component
public class MockPgClientAdapter implements PgClientPort {

    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        // Mock PG authorization - returns transaction ID
        return "PG-" + UUID.randomUUID().toString();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        // Mock PG capture - would call real PG API
        // Simulate success
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount) {
        // Mock PG refund - would call real PG API
        // Simulate success
    }
}
