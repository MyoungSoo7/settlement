package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementHoldbackTest {

    @Test
    @DisplayName("applyHoldback: 30% 보류 → holdbackAmount = netAmount × 0.30")
    void applyHoldback_normal() {
        // 100,000 결제, 3.5% 수수료 → net 96,500
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));

        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));

        // 96,500 * 0.30 = 28,950
        assertThat(s.getHoldbackAmount()).isEqualByComparingTo("28950.00");
        assertThat(s.getHoldbackRate()).isEqualByComparingTo("0.30");
        assertThat(s.getHoldbackReleaseDate()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(s.isHoldbackReleased()).isFalse();
    }

    @Test
    @DisplayName("applyHoldback: 0% 보류 → 즉시 released=true")
    void applyHoldback_zero_immediatelyReleased() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.now(), new BigDecimal("0.02"));

        s.applyHoldback(BigDecimal.ZERO, null);

        assertThat(s.getHoldbackAmount()).isEqualByComparingTo("0");
        assertThat(s.isHoldbackReleased()).isTrue();
    }

    @Test
    @DisplayName("getImmediatePayoutAmount: 보류 전엔 net - holdback, 해제 후엔 net 전액")
    void immediatePayout() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));

        // net=96,500, holdback=28,950 → 즉시 지급=67,550
        assertThat(s.getImmediatePayoutAmount()).isEqualByComparingTo("67550.00");

        s.releaseHoldback(LocalDate.of(2026, 5, 31));
        assertThat(s.getImmediatePayoutAmount()).isEqualByComparingTo("96500.00");
    }

    @Test
    @DisplayName("releaseHoldback: 아직 release 시점이 아니면 IllegalStateException")
    void releaseHoldback_tooEarly() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));

        assertThatThrownBy(() -> s.releaseHoldback(LocalDate.of(2026, 5, 30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("consumeHoldbackForRefund: 보류액 충분 시 환불액만큼 차감, 셀러 net 영향 없음")
    void consumeHoldback_sufficientHoldback() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));
        // holdback=28,950

        BigDecimal consumed = s.consumeHoldbackForRefund(new BigDecimal("10000"));

        assertThat(consumed).isEqualByComparingTo("10000");
        assertThat(s.getHoldbackAmount()).isEqualByComparingTo("18950.00");
        assertThat(s.isHoldbackReleased()).isFalse();
    }

    @Test
    @DisplayName("consumeHoldbackForRefund: 환불액이 보류액보다 크면 보류 전액 차감 + 자동 released")
    void consumeHoldback_refundExceedsHoldback() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));

        // holdback=28,950 인데 50,000 환불 요청
        BigDecimal consumed = s.consumeHoldbackForRefund(new BigDecimal("50000"));

        assertThat(consumed).isEqualByComparingTo("28950.00");  // 보류 전액만 차감
        assertThat(s.getHoldbackAmount()).isEqualByComparingTo("0.00");
        assertThat(s.isHoldbackReleased()).isTrue();             // 보류금 소진 → released
    }

    @Test
    @DisplayName("consumeHoldbackForRefund: 이미 released 면 0 반환 (환불은 일반 흐름)")
    void consumeHoldback_alreadyReleased() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(BigDecimal.ZERO, null); // 즉시 released

        BigDecimal consumed = s.consumeHoldbackForRefund(new BigDecimal("10000"));

        assertThat(consumed).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("isHoldbackReleasable: release_date 도달 + 미해제 + 잔액 있을 때만 true")
    void releasableCheck() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.of(2026, 5, 1), new BigDecimal("0.0350"));
        s.applyHoldback(new BigDecimal("0.30"), LocalDate.of(2026, 5, 31));

        // release 전날 — false
        assertThat(s.isHoldbackReleasable(LocalDate.of(2026, 5, 30))).isFalse();
        // release 당일 — true
        assertThat(s.isHoldbackReleasable(LocalDate.of(2026, 5, 31))).isTrue();
        // release 이후 — true
        assertThat(s.isHoldbackReleasable(LocalDate.of(2026, 6, 1))).isTrue();

        s.releaseHoldback(LocalDate.of(2026, 5, 31));
        // 이미 해제됨 — false
        assertThat(s.isHoldbackReleasable(LocalDate.of(2026, 6, 1))).isFalse();
    }

    @Test
    @DisplayName("validation: 보류율이 1 초과면 IllegalArgumentException")
    void validation() {
        Settlement s = Settlement.createFromPayment(1L, 10L,
                new BigDecimal("100000"), LocalDate.now(), new BigDecimal("0.03"));

        assertThatThrownBy(() -> s.applyHoldback(new BigDecimal("1.5"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.applyHoldback(new BigDecimal("-0.1"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
