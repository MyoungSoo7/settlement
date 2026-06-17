package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.architecture.AuditCrossRead;
import github.lms.lemuel.pgreconciliation.application.port.out.LoadInternalPaymentsForReconciliationPort;
import github.lms.lemuel.pgreconciliation.domain.InternalPaymentRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 대사용 내부 결제 read-model.
 *
 * <p>settlement-service 가 order-service 코드를 import 하지 않으면서 payments 테이블을
 * 직접 SQL 로 읽는다. {@code DailyTotalsJdbcAdapter} 와 동일 패턴.
 *
 * <p>매칭 키: {@code pg_transaction_id} — PG 파일에서 받은 거래 ID 와 1:1 매칭.
 */
@Repository
@AuditCrossRead("PG 대사 — order 원천(opslab.payments)을 pg_transaction_id 로 직독해 PG 파일과 대조")
public class InternalPaymentsForReconJdbcAdapter implements LoadInternalPaymentsForReconciliationPort {

    private final JdbcTemplate jdbcTemplate;

    public InternalPaymentsForReconJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<InternalPaymentRow> loadByCapturedDate(LocalDate date) {
        // CAPTURED 또는 REFUNDED 상태이면서 captured_at::date 가 대상일과 일치하는 결제.
        // pg_transaction_id 가 없는 결제는 대사 대상이 아니므로 제외.
        String sql = """
            SELECT id, pg_transaction_id, amount, refunded_amount, captured_at::date AS captured_date
              FROM opslab.payments
             WHERE pg_transaction_id IS NOT NULL
               AND status IN ('CAPTURED', 'REFUNDED')
               AND captured_at::date = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new InternalPaymentRow(
                rs.getLong("id"),
                rs.getString("pg_transaction_id"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("refunded_amount"),
                rs.getObject("captured_date", LocalDate.class)
        ), date);
    }
}
