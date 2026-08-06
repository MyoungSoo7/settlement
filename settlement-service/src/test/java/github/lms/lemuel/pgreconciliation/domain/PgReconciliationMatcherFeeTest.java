package github.lms.lemuel.pgreconciliation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 알고리즘의 실입금 검증 편입 — 금액이 맞아도 자금이 어긋나면 잡아낸다.
 *
 * <p>기존 매칭은 "PG 매출 == 내부 매출" 만 봤다. 그 통과 지점 뒤에서 수수료가 잘못 빠져나가면
 * 대사는 전부 정상이라고 보고하는데 통장에는 돈이 덜 들어온다. 실무 정산 사고의 큰 축이 여기다.
 */
class PgReconciliationMatcherFeeTest {

    private static final Long RUN = 1L;
    private static final LocalDate D = LocalDate.of(2026, 8, 5);

    private static InternalPaymentRow internal(long paymentId, String txnId, String amount) {
        return new InternalPaymentRow(paymentId, txnId, new BigDecimal(amount), BigDecimal.ZERO, D);
    }

    private static PgTransactionRow pgWithDeposit(String txnId, String amount,
                                                  PgFeeBreakdown fees, String netDeposit) {
        return PgTransactionRow.of(txnId, new BigDecimal(amount), BigDecimal.ZERO, fees,
                netDeposit == null ? null : new BigDecimal(netDeposit), D, null, null);
    }

    @Test
    @DisplayName("금액 일치 + 실입금 정합 → 불일치 없음, matched 로 집계")
    void amountAndDepositBothOk() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                        null, null, null, null, null), "9670"));
        var internal = List.of(internal(1L, "TX-1", "10000"));

        var result = PgReconciliationMatcher.match(RUN, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("금액은 맞는데 실입금이 어긋나면 FEE_MISMATCH — 매칭 통과 뒤에서 새는 자금을 잡는다")
    void amountOkButDepositMismatchIsReported() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                        null, null, null, null, null), "9500"));   // 계산은 9670
        var internal = List.of(internal(1L, "TX-1", "10000"));

        var result = PgReconciliationMatcher.match(RUN, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);   // 매출 자체는 일치
        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().getFirst();
        assertThat(d.getType()).isEqualTo(DiscrepancyType.FEE_MISMATCH);
        assertThat(d.getPgTransactionId()).isEqualTo("TX-1");
        assertThat(d.getPaymentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("FEE_MISMATCH 는 신고 실입금과 계산 실입금을 양쪽 다 보존 — 운영자가 차액 원인을 본다")
    void feeMismatchKeepsBothSides() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.legacy(new BigDecimal("300")), "9500"));   // 계산 9700
        var internal = List.of(internal(1L, "TX-1", "10000"));

        var d = PgReconciliationMatcher.match(RUN, pg, internal).discrepancies().getFirst();

        assertThat(d.getInternalAmount()).isEqualByComparingTo("9700");  // 우리 계산 = 정답지
        assertThat(d.getPgAmount()).isEqualByComparingTo("9500");        // PG 신고
        assertThat(d.getDifference()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("실입금 미신고(레거시 파일)면 FEE_MISMATCH 를 만들지 않는다 — 정보 부재는 불일치가 아니다")
    void unreportedDepositProducesNoDiscrepancy() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.legacy(new BigDecimal("300")), null));
        var internal = List.of(internal(1L, "TX-1", "10000"));

        var result = PgReconciliationMatcher.match(RUN, pg, internal);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("1원 미만 실입금 차이는 통과 — PG 라운딩 관행")
    void subWonDepositDiffTolerated() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.of(new BigDecimal("300.50"), null, null, null, null, null, null), "9699.90"));
        var internal = List.of(internal(1L, "TX-1", "10000"));

        assertThat(PgReconciliationMatcher.match(RUN, pg, internal).discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("금액 불일치 건은 FEE_MISMATCH 를 중복 발행하지 않는다 — 원인 하나에 보고 하나")
    void amountMismatchDoesNotAlsoEmitFeeMismatch() {
        var pg = List.of(pgWithDeposit("TX-1", "10000",
                PgFeeBreakdown.legacy(new BigDecimal("300")), "9000"));   // 실입금도 어긋남
        var internal = List.of(internal(1L, "TX-1", "12000"));            // 매출부터 불일치

        var result = PgReconciliationMatcher.match(RUN, pg, internal);

        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().getFirst().getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("내부에 없는 PG 행(MISSING_INTERNAL)도 FEE_MISMATCH 를 겹쳐 내지 않는다")
    void missingInternalDoesNotAlsoEmitFeeMismatch() {
        var pg = List.of(pgWithDeposit("TX-9", "10000",
                PgFeeBreakdown.legacy(new BigDecimal("300")), "9000"));

        var result = PgReconciliationMatcher.match(RUN, pg, List.of());

        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().getFirst().getType()).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
    }

    @Test
    @DisplayName("FEE_MISMATCH 는 자동 보정 대상이 아니다 — 자금 차이는 운영자 판단이 필요하다")
    void feeMismatchIsNotAutoCorrectable() {
        assertThat(DiscrepancyType.FEE_MISMATCH.isAutoCorrectable()).isFalse();
    }
}
