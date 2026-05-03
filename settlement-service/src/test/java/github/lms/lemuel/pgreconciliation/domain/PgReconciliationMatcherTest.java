package github.lms.lemuel.pgreconciliation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 정산파일 vs 내부 결제 비교 알고리즘 단위 테스트.
 *
 * <p>도메인 순수 로직 — Spring 컨텍스트 / DB 의존 0. 매칭 분류가 정확한지가 핵심.
 */
class PgReconciliationMatcherTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 28);

    @Test
    @DisplayName("양쪽 동일 amount → MATCHED 카운트 증가, 차이 발생 없음")
    void exactMatch() {
        var pg = List.of(pgRow("TOSS:1", "10000", "0"));
        var internal = List.of(internalRow(1L, "TOSS:1", "10000", "0"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("양쪽 차이 1원 미만 → ROUNDING_DIFF 자동 보정, AUTO_CORRECTED 상태")
    void roundingDifference_autoCorrected() {
        // 내부 9999.50 / PG 10000.00 → 차이 0.50 (1원 미만)
        var pg = List.of(pgRow("TOSS:1", "10000.00", "0"));
        var internal = List.of(internalRow(1L, "TOSS:1", "9999.50", "0"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.matchedCount()).isZero();
        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().get(0);
        assertThat(d.getType()).isEqualTo(DiscrepancyType.ROUNDING_DIFF);
        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.AUTO_CORRECTED);
        assertThat(d.getResolvedBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("양쪽 차이 1원 이상 → AMOUNT_MISMATCH, PENDING 상태로 운영자 검토 큐 진입")
    void amountMismatch_pending() {
        var pg = List.of(pgRow("TOSS:1", "10500", "0"));
        var internal = List.of(internalRow(1L, "TOSS:1", "10000", "0"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().get(0);
        assertThat(d.getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.PENDING);
        assertThat(d.getDifference()).isEqualByComparingTo("500"); // pg - internal
    }

    @Test
    @DisplayName("PG 파일에만 있는 거래 → MISSING_INTERNAL (가장 위험: 매출 누락 의심)")
    void missingInternal() {
        var pg = List.of(pgRow("TOSS:phantom", "30000", "0"));
        var internal = List.<InternalPaymentRow>of();

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().get(0);
        assertThat(d.getType()).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
        assertThat(d.getPaymentId()).isNull();
        assertThat(d.getInternalAmount()).isNull();
        assertThat(d.getPgAmount()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("내부에만 있고 PG 파일에 없는 거래 → MISSING_PG")
    void missingPg() {
        var pg = List.<PgTransactionRow>of();
        var internal = List.of(internalRow(7L, "TOSS:lonely", "5000", "0"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().get(0);
        assertThat(d.getType()).isEqualTo(DiscrepancyType.MISSING_PG);
        assertThat(d.getPaymentId()).isEqualTo(7L);
        assertThat(d.getPgAmount()).isNull();
        assertThat(d.getInternalAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("PG 파일 안에서 같은 거래키 2회 등장 → DUPLICATE")
    void duplicate() {
        var pg = List.of(
                pgRow("TOSS:dup", "10000", "0"),
                pgRow("TOSS:dup", "10000", "0")
        );
        var internal = List.of(internalRow(1L, "TOSS:dup", "10000", "0"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        // 첫 번째 처리에서 DUPLICATE 만 보고됨
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).getType()).isEqualTo(DiscrepancyType.DUPLICATE);
    }

    @Test
    @DisplayName("환불 반영 — 양쪽 net amount 가 일치하면 MATCHED")
    void netAmount_withRefund() {
        // PG: 10000 매출, 3000 환불 = net 7000
        // 내부: 10000 매출, 3000 환불 = net 7000
        var pg = List.of(pgRow("TOSS:1", "10000", "3000"));
        var internal = List.of(internalRow(1L, "TOSS:1", "10000", "3000"));

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("복합 시나리오: 일치 1건 + 누락 1건 + 미스매치 1건")
    void mixedScenario() {
        var pg = List.of(
                pgRow("TOSS:match", "10000", "0"),       // MATCHED
                pgRow("TOSS:phantom", "5000", "0"),       // MISSING_INTERNAL
                pgRow("KCP:big-diff", "20000", "0")       // AMOUNT_MISMATCH
        );
        var internal = List.of(
                internalRow(1L, "TOSS:match", "10000", "0"),
                internalRow(2L, "KCP:big-diff", "21500", "0"),
                internalRow(3L, "NICE:lonely", "8000", "0") // MISSING_PG
        );

        var result = PgReconciliationMatcher.match(99L, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.discrepancies()).hasSize(3);
        assertThat(result.discrepancies()).extracting("type")
                .containsExactlyInAnyOrder(
                        DiscrepancyType.MISSING_INTERNAL,
                        DiscrepancyType.AMOUNT_MISMATCH,
                        DiscrepancyType.MISSING_PG);
    }

    private static PgTransactionRow pgRow(String txnId, String amount, String refund) {
        return new PgTransactionRow(txnId, new BigDecimal(amount), new BigDecimal(refund),
                BigDecimal.ZERO, TODAY);
    }

    private static InternalPaymentRow internalRow(Long id, String txnId, String amount, String refund) {
        return new InternalPaymentRow(id, txnId, new BigDecimal(amount), new BigDecimal(refund), TODAY);
    }
}
