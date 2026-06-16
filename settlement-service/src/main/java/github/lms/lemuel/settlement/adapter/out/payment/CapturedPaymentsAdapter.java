package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
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
 * <p>ADR 0020 Phase 3 — order payments 를 @Immutable 로 직접 매핑하던 read-model 대신
 * settlement 소유 로컬 프로젝션({@link SettlementPaymentViewJpaEntity}, PaymentCaptured 이벤트로 적재)을
 * 조회한다. 이로써 정산 배치의 결제 조회가 order 테이블 직접 매핑에서 분리된다.</p>
 */
@Component
@RequiredArgsConstructor
public class CapturedPaymentsAdapter implements LoadCapturedPaymentsPort {

    private final SettlementPaymentViewRepository paymentViewRepository;

    @Override
    public List<CapturedPaymentInfo> findCapturedPaymentsByDate(LocalDate settlementDate) {
        LocalDateTime startDateTime = settlementDate.atStartOfDay();
        LocalDateTime endDateTime = settlementDate.plusDays(1).atStartOfDay();

        List<SettlementPaymentViewJpaEntity> payments = paymentViewRepository
                .findByCapturedAtBetweenAndStatus(startDateTime, endDateTime, "CAPTURED");

        return payments.stream()
                .map(payment -> new CapturedPaymentInfo(
                        payment.getPaymentId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        payment.getCapturedAt()
                ))
                .collect(Collectors.toList());
    }
}
