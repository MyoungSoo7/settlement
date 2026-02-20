package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaEntity;
import github.lms.lemuel.payment.adapter.out.persistence.PaymentJpaRepository;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settlement에서 Payment 정보를 조회하는 Adapter
 * Payment 모듈의 JPA Repository를 사용하되, 인터페이스로 의존성 분리
 */
@Component
@RequiredArgsConstructor
public class CapturedPaymentsAdapter implements LoadCapturedPaymentsPort {

    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate) {
        // LocalDate를 LocalDateTime 범위로 변환 (해당 날짜 00:00:00 ~ 23:59:59)
        LocalDateTime startDateTime = settlementDate.atStartOfDay();
        LocalDateTime endDateTime = settlementDate.plusDays(1).atStartOfDay();

        // Payment JPA Entity를 직접 조회
        List<PaymentJpaEntity> payments = paymentJpaRepository
                .findByCapturedAtBetweenAndStatus(startDateTime, endDateTime, "CAPTURED");

        // DTO로 변환하여 반환 (도메인 의존성 제거)
        return payments.stream()
                .map(payment -> new CapturedPaymentInfo(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        payment.getCapturedAt()
                ))
                .collect(Collectors.toList());
    }
}
