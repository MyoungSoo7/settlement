package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 담보 재평가 이력 · 마진콜 생명주기 (Phase 2).
 *
 * <p><b>재평가는 평가액을 덮어쓰지 않는다.</b> {@link Collateral#getAppraisedValue()} 는 설정 시점의
 * 심사 근거라 불변이고, 시가 변동은 {@link CollateralRevaluation} 이력 행으로 쌓인다. 덮어쓰면
 * 이미 실행된 대출의 한도 산정 근거를 사후에 바꿔 버려 재현이 불가능해진다.
 */
class MarginCallTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 1, 9, 0);

    // ─── 재평가 이력 ──────────────────────────────────────────────────────────

    @Test
    void 재평가는_담보와_시점과_출처를_남긴다() {
        CollateralRevaluation revaluation = CollateralRevaluation.of(
                3001L, new BigDecimal("420000000"), "MARKET_SERVICE", AT);

        assertThat(revaluation.collateralId()).isEqualTo(3001L);
        assertThat(revaluation.revaluedValue()).isEqualByComparingTo("420000000");
        assertThat(revaluation.source()).isEqualTo("MARKET_SERVICE");
        assertThat(revaluation.revaluedAt()).isEqualTo(AT);
    }

    @Test
    void 재평가액은_통화정책_스케일로_정규화된다() {
        assertThat(CollateralRevaluation.of(1L, new BigDecimal("420000000"), "X", AT)
                .revaluedValue().scale()).isEqualTo(2);
    }

    @Test
    void 재평가액이_0이하면_예외() {
        assertThatThrownBy(() -> CollateralRevaluation.of(1L, BigDecimal.ZERO, "X", AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 재평가_필수값이_없으면_예외() {
        assertThatThrownBy(() -> CollateralRevaluation.of(null, BigDecimal.TEN, "X", AT))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CollateralRevaluation.of(1L, BigDecimal.TEN, "  ", AT))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CollateralRevaluation.of(1L, BigDecimal.TEN, "X", null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 마진콜 상태 전이표 ───────────────────────────────────────────────────

    private static Set<MarginCallStatus> allowedFrom(MarginCallStatus from) {
        return switch (from) {
            case OPEN -> EnumSet.of(MarginCallStatus.RESOLVED, MarginCallStatus.ESCALATED);
            case RESOLVED, ESCALATED -> EnumSet.noneOf(MarginCallStatus.class);
        };
    }

    @Test
    void 마진콜_전이표_전조합이_정본과_일치한다() {
        for (MarginCallStatus from : MarginCallStatus.values()) {
            Set<MarginCallStatus> allowed = allowedFrom(from);
            for (MarginCallStatus to : MarginCallStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s → %s", from, to)
                        .isEqualTo(allowed.contains(to));
            }
        }
    }

    // ─── 마진콜 생명주기 ──────────────────────────────────────────────────────

    private static MarginCall opened() {
        return MarginCall.open(7001L, 3001L, new BigDecimal("20000000"), AT);
    }

    @Test
    void 신규_마진콜은_OPEN_이고_요구액을_보존한다() {
        MarginCall call = opened();

        assertThat(call.getStatus()).isEqualTo(MarginCallStatus.OPEN);
        assertThat(call.getLoanId()).isEqualTo(7001L);
        assertThat(call.getCollateralId()).isEqualTo(3001L);
        assertThat(call.getRequiredAmount()).isEqualByComparingTo("20000000");
        assertThat(call.getOpenedAt()).isEqualTo(AT);
        assertThat(call.getClosedAt()).isNull();
    }

    @Test
    void 요구액이_0이하면_마진콜을_열_수_없다() {
        // 담보가 충분한데 마진콜을 여는 것은 논리 오류다.
        assertThatThrownBy(() -> MarginCall.open(1L, 1L, BigDecimal.ZERO, AT))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 담보_보충으로_해소하면_RESOLVED_와_종료시각이_남는다() {
        MarginCall call = opened();
        LocalDateTime closedAt = AT.plusDays(2);

        call.resolve(closedAt);

        assertThat(call.getStatus()).isEqualTo(MarginCallStatus.RESOLVED);
        assertThat(call.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    void 미해소로_강제처분_단계로_넘어가면_ESCALATED() {
        MarginCall call = opened();
        call.escalate(AT.plusDays(7));

        assertThat(call.getStatus()).isEqualTo(MarginCallStatus.ESCALATED);
        assertThat(call.getClosedAt()).isEqualTo(AT.plusDays(7));
    }

    @Test
    void 종료된_마진콜은_다시_전이할_수_없다() {
        MarginCall resolved = opened();
        resolved.resolve(AT.plusDays(1));
        assertThatThrownBy(() -> resolved.escalate(AT.plusDays(2)))
                .isInstanceOf(InvalidLoanStateException.class);

        MarginCall escalated = opened();
        escalated.escalate(AT.plusDays(1));
        assertThatThrownBy(() -> escalated.resolve(AT.plusDays(2)))
                .isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 종료시각은_필수다() {
        assertThatThrownBy(() -> opened().resolve(null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 종료시각은_발생시각보다_앞설_수_없다() {
        assertThatThrownBy(() -> opened().resolve(AT.minusDays(1)))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 활성_여부를_알려준다() {
        MarginCall call = opened();
        assertThat(call.isOpen()).isTrue();
        call.resolve(AT.plusDays(1));
        assertThat(call.isOpen()).isFalse();
    }
}
