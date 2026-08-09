package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;
import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-G4 상태머신 + 생성 팩토리 검증 — REQUESTED→PAID 1개 전이만, 0원 payout 생성 거부,
 * 산출근거 스냅샷 보존.
 */
@DisplayName("일반지급 애그리거트 (D-G4 상태머신 · D-G5 근거 스냅샷)")
class GeneralPayoutTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    private static PayoutQuote quote(String amount) {
        return new PayoutQuote(new BigDecimal(amount), new BigDecimal("3700000.00"),
                new BigDecimal("0.6000"), 36, 37);
    }

    private static GeneralPayout requested() {
        return GeneralPayout.request(UUID.randomUUID().toString(), "POL-20260808-abcd1234",
                GeneralPayoutType.SURRENDER_REFUND, quote("2220000.00"), TODAY);
    }

    // ────────────────────────────────────────────────────────────────────
    // 생성 팩토리
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("request: REQUESTED 로 태어나고 payoutId 가 채번되며 산출근거가 스냅샷된다")
    void requestCreatesRequestedPayoutWithQuoteSnapshot() {
        GeneralPayout payout = requested();

        assertThat(payout.getStatus()).isEqualTo(GeneralPayoutStatus.REQUESTED);
        assertThat(payout.getPayoutId()).isNotBlank();
        assertThat(UUID.fromString(payout.getPayoutId())).isNotNull(); // UUID 형식
        assertThat(payout.getAmount()).isEqualByComparingTo("2220000.00");
        assertThat(payout.getPaidPremiumTotal()).isEqualByComparingTo("3700000.00");
        assertThat(payout.getAppliedRate()).isEqualByComparingTo("0.60");
        assertThat(payout.getElapsedMonths()).isEqualTo(36);
        assertThat(payout.getInstallmentCount()).isEqualTo(37);
        assertThat(payout.getRequestedOn()).isEqualTo(TODAY);
        assertThat(payout.getPaidOn()).isNull();
    }

    @Test
    @DisplayName("0원·음수 지급 생성은 거부된다 — 0원 payout 행은 존재하지 않는다 (D-G3)")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> GeneralPayout.request(
                UUID.randomUUID().toString(), "POL-20260808-abcd1234",
                GeneralPayoutType.SURRENDER_REFUND, quote("0.00"), TODAY))
                .isInstanceOf(InvalidGeneralPayoutException.class);

        assertThatThrownBy(() -> GeneralPayout.request(
                UUID.randomUUID().toString(), "POL-20260808-abcd1234",
                GeneralPayoutType.SURRENDER_REFUND, quote("-1.00"), TODAY))
                .isInstanceOf(InvalidGeneralPayoutException.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // D-G4 상태머신
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markPaid: REQUESTED → PAID + 지급일 스탬프")
    void markPaidTransitionsAndStampsPaidOn() {
        GeneralPayout payout = requested();

        payout.markPaid(TODAY.plusDays(1));

        assertThat(payout.getStatus()).isEqualTo(GeneralPayoutStatus.PAID);
        assertThat(payout.getPaidOn()).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    @DisplayName("이중 지급 차단 — PAID 에서 markPaid 재호출은 비허용 전이")
    void rejectsDoublePayment() {
        GeneralPayout payout = requested();
        payout.markPaid(TODAY);

        assertThatThrownBy(() -> payout.markPaid(TODAY.plusDays(1)))
                .isInstanceOf(InvalidGeneralPayoutTransitionException.class);
    }

    @Test
    @DisplayName("전이표: REQUESTED→PAID 만 허용, PAID 는 terminal")
    void transitionTable() {
        assertThat(GeneralPayoutStatus.REQUESTED.canTransitionTo(GeneralPayoutStatus.PAID)).isTrue();
        assertThat(GeneralPayoutStatus.PAID.canTransitionTo(GeneralPayoutStatus.REQUESTED)).isFalse();
        assertThat(GeneralPayoutStatus.PAID.canTransitionTo(GeneralPayoutStatus.PAID)).isFalse();
        assertThat(GeneralPayoutStatus.REQUESTED.canTransitionTo(GeneralPayoutStatus.REQUESTED)).isFalse();

        assertThat(GeneralPayoutStatus.REQUESTED.isTerminal()).isFalse();
        assertThat(GeneralPayoutStatus.PAID.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("재구성(빌더) 시 PAID 상태 그대로 복원된다 — 전이 없이")
    void rehydratesPaidStateWithoutTransition() {
        GeneralPayout payout = GeneralPayout.builder()
                .id(7L)
                .payoutId(UUID.randomUUID().toString())
                .policyId(UUID.randomUUID().toString())
                .policyNumber("POL-20260101-11112222")
                .payoutType(GeneralPayoutType.MATURITY_BENEFIT)
                .amount(new BigDecimal("12000000.00"))
                .paidPremiumTotal(new BigDecimal("12000000.00"))
                .appliedRate(BigDecimal.ONE)
                .elapsedMonths(120)
                .installmentCount(120)
                .status(GeneralPayoutStatus.PAID)
                .requestedOn(TODAY.minusDays(1))
                .paidOn(TODAY)
                .build();

        assertThat(payout.getStatus()).isEqualTo(GeneralPayoutStatus.PAID);
        assertThat(payout.getPaidOn()).isEqualTo(TODAY);
    }
}
