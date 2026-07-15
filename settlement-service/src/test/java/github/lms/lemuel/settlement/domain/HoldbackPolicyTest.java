package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldbackPolicyTest {

    @Test
    @DisplayName("forTier: NORMAL=30%/30일, VIP=10%/14일, STRATEGIC=0%/0일")
    void perTierDefaults() {
        var normal = HoldbackPolicy.forTier(SellerTier.NORMAL);
        var vip = HoldbackPolicy.forTier(SellerTier.VIP);
        var strategic = HoldbackPolicy.forTier(SellerTier.STRATEGIC);

        assertThat(normal.rate()).isEqualByComparingTo("0.30");
        assertThat(normal.releaseDays()).isEqualTo(30);
        assertThat(vip.rate()).isEqualByComparingTo("0.10");
        assertThat(vip.releaseDays()).isEqualTo(14);
        assertThat(strategic.rate()).isEqualByComparingTo("0");
        assertThat(strategic.releaseDays()).isEqualTo(0);
    }

    @Test
    @DisplayName("computeReleaseDate: 영업일 기준 N 일 후")
    void computeReleaseDate_businessDays() {
        var policy = HoldbackPolicy.forTier(SellerTier.VIP); // 14 일
        // 2026-04-28 화 → 14 영업일 후
        // 4/29 수(1) → 4/30 목(2) → 5/1 금(3) → 주말 → 5/4 월(4) → 5/5 어린이날 스킵
        // → 5/6 수(5) → 5/7 목(6) → 5/8 금(7) → 주말 → 5/11 월(8) → 5/12 화(9)
        // → 5/13 수(10) → 5/14 목(11) → 5/15 금(12) → 주말 → 5/18 월(13) → 5/19 화(14)
        LocalDate result = policy.computeReleaseDate(LocalDate.of(2026, 4, 28));
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 19));
    }

    @Test
    @DisplayName("computeReleaseDate: 0일이면 settlementDate 그대로")
    void zeroDays_sameDate() {
        var policy = HoldbackPolicy.forTier(SellerTier.STRATEGIC);
        LocalDate d = LocalDate.of(2026, 4, 28);
        assertThat(policy.computeReleaseDate(d)).isEqualTo(d);
    }

    @Test
    @DisplayName("validation: rate 범위 + releaseDays 음수")
    void validation() {
        assertThatThrownBy(() -> new HoldbackPolicy(new BigDecimal("-0.1"), 10))
                .isInstanceOf(SettlementInvariantViolationException.class);
        assertThatThrownBy(() -> new HoldbackPolicy(new BigDecimal("1.5"), 10))
                .isInstanceOf(SettlementInvariantViolationException.class);
        assertThatThrownBy(() -> new HoldbackPolicy(new BigDecimal("0.3"), -1))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }
}
