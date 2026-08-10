package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidCommissionClosingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 월 수수료 마감 스냅샷 도메인 테스트 — 생성 검증 + 재구성 왕복.
 */
@DisplayName("CommissionClosing 월 마감 스냅샷")
class CommissionClosingTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);

    @Test
    @DisplayName("정상 마감 — closingId 자동 채번, 필드 보존")
    void closesWithValidInputs() {
        CommissionClosing closing = CommissionClosing.close(
                "fc-100", JULY, new BigDecimal("99999.96"), 12);

        assertThat(closing.getClosingId()).isNotBlank();
        assertThat(closing.getFcId()).isEqualTo("fc-100");
        assertThat(closing.getClosingMonth()).isEqualTo(JULY);
        assertThat(closing.getTotalPaidAmount()).isEqualByComparingTo(new BigDecimal("99999.96"));
        assertThat(closing.getInstallmentCount()).isEqualTo(12);
        assertThat(closing.getId()).isNull();  // 신규 — 아직 미저장
    }

    @Test
    @DisplayName("지급 실적 0원 마감도 허용한다 (전액 보류된 달)")
    void allowsZeroAmountClosing() {
        CommissionClosing closing = CommissionClosing.close("fc-100", JULY, BigDecimal.ZERO, 0);

        assertThat(closing.getTotalPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("음수 합계·음수 회차 수는 거부한다")
    void rejectsNegativeInputs() {
        assertThatThrownBy(() ->
                CommissionClosing.close("fc-100", JULY, new BigDecimal("-0.01"), 1))
                .isInstanceOf(InvalidCommissionClosingException.class);

        assertThatThrownBy(() ->
                CommissionClosing.close("fc-100", JULY, BigDecimal.ONE, -1))
                .isInstanceOf(InvalidCommissionClosingException.class);
    }

    @Test
    @DisplayName("필수 입력 null 은 거부한다")
    void rejectsNullInputs() {
        assertThatThrownBy(() -> CommissionClosing.close(null, JULY, BigDecimal.ONE, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CommissionClosing.close("fc-100", null, BigDecimal.ONE, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CommissionClosing.close("fc-100", JULY, null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydrate — DB 행 전 필드를 손실 없이 재구성한다")
    void rehydratesEveryField() {
        CommissionClosing closing = CommissionClosing.rehydrate(
                7L, "11111111-1111-1111-1111-111111111111", "fc-200",
                JULY, new BigDecimal("12345.67"), 3);

        assertThat(closing.getId()).isEqualTo(7L);
        assertThat(closing.getClosingId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(closing.getFcId()).isEqualTo("fc-200");
        assertThat(closing.getClosingMonth()).isEqualTo(JULY);
        assertThat(closing.getTotalPaidAmount()).isEqualByComparingTo(new BigDecimal("12345.67"));
        assertThat(closing.getInstallmentCount()).isEqualTo(3);
    }
}
