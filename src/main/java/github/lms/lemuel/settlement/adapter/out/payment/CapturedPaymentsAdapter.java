package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaEntity;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CapturedPaymentsAdapter implements LoadCapturedPaymentsPort {

    private final PaymentJpaRepository paymentJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate) {
        LocalDateTime startDateTime = settlementDate.atStartOfDay();
        LocalDateTime endDateTime = settlementDate.plusDays(1).atStartOfDay();

        List<PaymentJpaEntity> payments = paymentJpaRepository
                .findByCapturedAtBetweenAndStatus(startDateTime, endDateTime, "CAPTURED");

        return payments.stream()
                .map(payment -> {
                    Long sellerId = resolveSellerId(payment.getOrderId());
                    return new CapturedPaymentInfo(
                            payment.getId(),
                            payment.getOrderId(),
                            sellerId,
                            payment.getAmount(),
                            payment.getCapturedAt()
                    );
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDateAndSeller(
            LocalDate settlementDate, Long sellerId) {
        return findCapturedPaymentsByDate(settlementDate).stream()
                .filter(p -> sellerId.equals(p.sellerId()))
                .collect(Collectors.toList());
    }

    private Long resolveSellerId(Long orderId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT p.seller_id FROM orders o JOIN products p ON o.product_id = p.id WHERE o.id = ?",
                    Long.class, orderId);
        } catch (Exception e) {
            return null; // seller not linked yet
        }
    }
}
