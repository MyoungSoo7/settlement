package github.lms.lemuel.integrity.adapter.out.persistence;

import github.lms.lemuel.integrity.application.port.out.BackfillTargetPort;
import github.lms.lemuel.ledger.domain.ReferenceType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * 백필 대상 상세 로더 — 탐지(INV-5 missingReverse)가 반환한 adjustment id 의 정정 입력만 읽는다.
 *
 * <p>탐지 쿼리는 {@link IntegrityQueryJdbcAdapter} 가 정본이고, 여기는 id → (출처, 양수 금액,
 * 조정일) 변환만 담당한다. 출처는 3-way at-most-one 제약이라 refund → chargeback → PG대사
 * 순서로 처음 채워진 축이 그 행의 출처다(탐지는 출처 없는 legacy 행을 반환하지 않는다).
 */
@Component
public class BackfillTargetJdbcAdapter implements BackfillTargetPort {

    private final JdbcClient jdbc;

    public BackfillTargetJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AdjustmentReversalTarget> loadAdjustmentTargets(Collection<Long> adjustmentIds) {
        if (adjustmentIds == null || adjustmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, settlement_id, refund_id, chargeback_id, reconciliation_discrepancy_id,
                               ABS(amount) AS amount, adjustment_date
                        FROM settlement_adjustments
                        WHERE id IN (:ids)
                        ORDER BY id
                        """)
                .param("ids", List.copyOf(adjustmentIds))
                .query(BackfillTargetJdbcAdapter::mapTarget)
                .list()
                .stream()
                // 방어: 출처 3축 모두 null 인 legacy 행(at-most-one 제약이 허용)은 정정 불가 —
                // 탐지가 이런 행을 반환하지 않는 것이 계약이지만, 계약이 깨져도 poison-pill 이 되지 않게 거른다.
                .filter(target -> target.referenceId() != null)
                .toList();
    }

    private static AdjustmentReversalTarget mapTarget(ResultSet rs, int rowNum) throws SQLException {
        Long refundId = rs.getObject("refund_id", Long.class);
        Long chargebackId = rs.getObject("chargeback_id", Long.class);
        Long discrepancyId = rs.getObject("reconciliation_discrepancy_id", Long.class);

        ReferenceType referenceType;
        Long referenceId;
        if (refundId != null) {
            referenceType = ReferenceType.REFUND;
            referenceId = refundId;
        } else if (chargebackId != null) {
            referenceType = ReferenceType.CHARGEBACK;
            referenceId = chargebackId;
        } else {
            referenceType = ReferenceType.PG_RECONCILIATION;
            referenceId = discrepancyId;
        }
        return new AdjustmentReversalTarget(
                rs.getLong("id"),
                rs.getLong("settlement_id"),
                referenceId,
                referenceType,
                rs.getBigDecimal("amount"),
                rs.getObject("adjustment_date", java.time.LocalDate.class));
    }
}
