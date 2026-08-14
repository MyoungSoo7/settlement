package github.lms.lemuel.pgreconciliation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 파일 한 줄의 <b>산술 정합성</b> — 매출 − 환불 − 공제 = 실입금.
 *
 * <p>이 검증이 없으면 "금액은 맞는데 돈이 덜 들어온" 상황을 대사가 통과시킨다. PG 가 신고한
 * 실입금과 우리가 계산한 값이 어긋나면 수수료 구조를 잘못 알고 있거나 PG 파일이 틀린 것이고,
 * 둘 다 자금 사고로 이어진다.
 */
class PgTransactionRowDepositTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 5);

    private static PgTransactionRow row(String amount, String refunded,
                                        PgFeeBreakdown fees, String netDeposit) {
        return PgTransactionRow.of("TX-1", new BigDecimal(amount), new BigDecimal(refunded),
                fees, netDeposit == null ? null : new BigDecimal(netDeposit), D, null, null);
    }

    @Test
    @DisplayName("예상 실입금 = 매출 − 환불 − 총공제")
    void expectedNetDeposit() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                        null, null, null, null, null), null);

        assertThat(r.netAmount()).isEqualByComparingTo("10000");
        assertThat(r.expectedNetDeposit()).isEqualByComparingTo("9670");
    }

    @Test
    @DisplayName("환불이 있으면 순매출에서 먼저 빼고 공제한다")
    void refundReducesBeforeFees() {
        PgTransactionRow r = row("25000", "1000",
                PgFeeBreakdown.legacy(new BigDecimal("750")), null);

        assertThat(r.netAmount()).isEqualByComparingTo("24000");
        assertThat(r.expectedNetDeposit()).isEqualByComparingTo("23250");
    }

    @Test
    @DisplayName("신고 실입금이 계산값과 같으면 불일치 없음")
    void depositMatches() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                        null, null, null, null, null), "9670");

        assertThat(r.hasDepositMismatch()).isFalse();
        assertThat(r.depositDifference()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("신고 실입금이 계산보다 적으면 불일치 — 차액은 음수(덜 들어옴)")
    void depositShortfallIsNegativeDifference() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.of(new BigDecimal("300"), new BigDecimal("30"),
                        null, null, null, null, null), "9600");

        assertThat(r.hasDepositMismatch()).isTrue();
        assertThat(r.depositDifference()).isEqualByComparingTo("-70");
    }

    @Test
    @DisplayName("신고 실입금이 계산보다 많으면 불일치 — 차액은 양수(더 들어옴)")
    void depositExcessIsPositiveDifference() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.legacy(new BigDecimal("300")), "9750");

        assertThat(r.hasDepositMismatch()).isTrue();
        assertThat(r.depositDifference()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("경계: 1원 미만 차이는 불일치로 보지 않는다 — PG 라운딩 관행")
    void subWonDifferenceTolerated() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.of(new BigDecimal("300.50"), null,
                        null, null, null, null, null), "9699.90");

        assertThat(r.depositDifference()).isEqualByComparingTo("0.40");
        assertThat(r.hasDepositMismatch()).isFalse();
    }

    @Test
    @DisplayName("경계: 정확히 1원 차이는 불일치로 본다")
    void exactlyOneWonIsMismatch() {
        PgTransactionRow r = row("10000", "0",
                PgFeeBreakdown.legacy(new BigDecimal("300")), "9701");

        assertThat(r.depositDifference()).isEqualByComparingTo("1");
        assertThat(r.hasDepositMismatch()).isTrue();
    }

    @Test
    @DisplayName("실입금 미신고(레거시 파일)면 검증 불가 — 불일치로 단정하지 않는다")
    void unreportedDepositIsNotMismatch() {
        PgTransactionRow r = row("10000", "0", PgFeeBreakdown.legacy(new BigDecimal("300")), null);

        assertThat(r.isDepositVerifiable()).isFalse();
        assertThat(r.hasDepositMismatch()).isFalse();
        assertThat(r.depositDifference()).isNull();
    }

    @Test
    @DisplayName("실입금이 신고되면 검증 가능")
    void reportedDepositIsVerifiable() {
        PgTransactionRow r = row("10000", "0", PgFeeBreakdown.legacy(new BigDecimal("300")), "9700");

        assertThat(r.isDepositVerifiable()).isTrue();
    }

    @Test
    @DisplayName("레거시 생성자는 공제를 단일 수수료로 흡수 — 기존 파서 하위호환")
    void legacyFactoryKeepsBehaviour() {
        PgTransactionRow r = PgTransactionRow.legacy("TX-9", new BigDecimal("10000"),
                new BigDecimal("1000"), new BigDecimal("300"), D);

        assertThat(r.netAmount()).isEqualByComparingTo("9000");
        assertThat(r.fees().totalDeduction()).isEqualByComparingTo("300");
        assertThat(r.isDepositVerifiable()).isFalse();
    }

    @Test
    @DisplayName("매입일·지급일은 선택 — 있으면 보존한다(자금 도착 시점 추적)")
    void keepsOptionalDates() {
        LocalDate purchase = LocalDate.of(2026, 8, 6);
        LocalDate payout = LocalDate.of(2026, 8, 10);
        PgTransactionRow r = PgTransactionRow.of("TX-2", new BigDecimal("10000"), BigDecimal.ZERO,
                PgFeeBreakdown.none(), null, D, purchase, payout);

        assertThat(r.purchaseDate()).isEqualTo(purchase);
        assertThat(r.payoutDate()).isEqualTo(payout);
    }
}
