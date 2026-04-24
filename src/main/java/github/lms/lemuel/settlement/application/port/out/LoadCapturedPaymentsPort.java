package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface LoadCapturedPaymentsPort {

    List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate);

    List<CapturedPaymentInfo> findCapturedPaymentsByDateAndSeller(LocalDate settlementDate, Long sellerId);

    record CapturedPaymentInfo(
            Long paymentId,
            Long orderId,
            Long sellerId,
            BigDecimal amount,
            LocalDateTime capturedAt
    ) {}
}
