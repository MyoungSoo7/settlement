package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentReadModelRepository;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settlement 가 정산 대상 결제(=CAPTURED 상태)를 조회하는 어댑터.
 *
 * <p>order-service 의 PaymentJpaEntity 를 직접 참조하지 않고, settlement-service 자체의
 * read-only projection ({@link SettlementPaymentReadModel}) 을 통해 조회한다.
 * 모듈 간 코드 의존성을 끊으면서, 단일 PG DB 는 공유 (포트폴리오 간소화).</p>
 */
@Component
@RequiredArgsConstructor
public class CapturedPaymentsAdapter implements LoadCapturedPaymentsPort {

    private final SettlementPaymentReadModelRepository paymentReadRepository;

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate) {
        LocalDateTime startDateTime = settlementDate.atStartOfDay();
        LocalDateTime endDateTime = settlementDate.plusDays(1).atStartOfDay();

        List<SettlementPaymentReadModel> payments = paymentReadRepository
                .findByCapturedAtBetweenAndStatus(startDateTime, endDateTime, "CAPTURED");

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
