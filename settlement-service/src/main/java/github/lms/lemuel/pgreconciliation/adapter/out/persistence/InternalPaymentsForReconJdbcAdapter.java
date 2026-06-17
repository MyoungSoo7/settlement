package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.pgreconciliation.application.port.out.LoadInternalPaymentsForReconciliationPort;
import github.lms.lemuel.pgreconciliation.domain.InternalPaymentRow;
import github.lms.lemuel.recon.OrderReconClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * PG 대사용 내부 결제 원장 어댑터 (ADR 0020 Phase 5 self-totals).
 *
 * <p>order 의 payments 를 직접 SQL 로 읽던 것을 {@link OrderReconClient} 내부 API 호출로 대체한다.
 * order 가 자기 DB 에서 CAPTURED/REFUNDED 결제 행을 산출해 반환하고, settlement 는 그 결과를
 * PG 파일과 대조한다 → settlement 가 order DB 를 직접 읽지 않는다(cross-DB 연결 0).
 */
@Repository
public class InternalPaymentsForReconJdbcAdapter implements LoadInternalPaymentsForReconciliationPort {

    private final OrderReconClient orderReconClient;

    public InternalPaymentsForReconJdbcAdapter(OrderReconClient orderReconClient) {
        this.orderReconClient = orderReconClient;
    }

    @Override
    public List<InternalPaymentRow> loadByCapturedDate(LocalDate date) {
        return orderReconClient.capturedPayments(date).stream()
                .map(r -> new InternalPaymentRow(
                        r.paymentId(),
                        r.pgTransactionId(),
                        r.amount(),
                        r.refundedAmount(),
                        r.capturedDate()))
                .toList();
    }
}
