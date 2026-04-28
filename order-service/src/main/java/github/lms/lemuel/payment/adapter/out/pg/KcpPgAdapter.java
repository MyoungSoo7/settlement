package github.lms.lemuel.payment.adapter.out.pg;

import github.lms.lemuel.payment.domain.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * KCP (NHN KCP) 어댑터.
 *
 * <p>국내 PG 시장 점유율 1·2 위. 카드·계좌이체·가상계좌 결제 처리.
 * 본 mock 구현은 라우팅 검증용이며, 운영에서는 KCP REST API 호출로 교체한다.
 */
@Component
public class KcpPgAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(KcpPgAdapter.class);
    private static final String CB_INSTANCE = "kcpPg";

    private static final Set<String> SUPPORTED = Set.of(
            "CARD", "BANK_TRANSFER", "VIRTUAL_ACCOUNT"
    );

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public KcpPgAdapter(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public PaymentGateway provider() {
        return PaymentGateway.KCP;
    }

    @Override
    public boolean supports(String paymentMethod) {
        return paymentMethod != null && SUPPORTED.contains(paymentMethod.toUpperCase());
    }

    @Override
    public boolean isHealthy() {
        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker(CB_INSTANCE).getState();
        return state != CircuitBreaker.State.OPEN;
    }

    @Override
    public String authorize(Long paymentId, BigDecimal amount, String paymentMethod) {
        log.debug("[KCP] authorize paymentId={}, amount={}, method={}", paymentId, amount, paymentMethod);
        return PaymentGateway.KCP.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + UUID.randomUUID();
    }

    @Override
    public void capture(String pgTransactionId, BigDecimal amount) {
        log.debug("[KCP] capture txnId={}, amount={}", pgTransactionId, amount);
    }

    @Override
    public void refund(String pgTransactionId, BigDecimal amount) {
        log.info("[KCP] refund txnId={}, amount={}", pgTransactionId, amount);
    }
}
