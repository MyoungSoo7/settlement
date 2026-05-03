package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.InternalPaymentRow;

import java.time.LocalDate;
import java.util.List;

/**
 * 대사용 내부 결제 원장 read-model 포트.
 *
 * <p>settlement-service 가 order-service 코드를 import 하지 않으면서 payments 테이블을
 * 직접 SQL 로 읽는 CQRS 전용 어댑터가 구현. {@code DailyTotalsJdbcAdapter} 와 동일 패턴.
 */
public interface LoadInternalPaymentsForReconciliationPort {

    /**
     * 해당 영업일에 CAPTURED 또는 환불 처리된 결제를 PG 거래키와 함께 반환.
     */
    List<InternalPaymentRow> loadByCapturedDate(LocalDate date);
}
