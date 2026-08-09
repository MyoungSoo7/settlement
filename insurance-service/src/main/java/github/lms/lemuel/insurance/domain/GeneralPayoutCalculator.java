package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidGeneralPayoutException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 일반지급 산출 도메인 서비스 (D-G2·D-G3) — 순수 static utility, 프레임워크 의존 0.
 *
 * <p><b>D-G2 — 기납입보험료는 정상납입 가정 산출</b>: 납입 원장이 없는 V1 한계를 명시적
 * 근사로 둔다.
 * <ul>
 *   <li>회차 보험료 = 연 보험료 × 납입주기(개월) / {@link GeneralPayoutConstants#MONTHS_PER_YEAR}
 *       (통화 최소단위 절사)</li>
 *   <li>납입 회차 수 = floor(경과월수 / 납입주기) + 1 — 기준일 도래분 포함
 *       (1회차는 효력일 당일 납입)</li>
 *   <li>만기보험금만 만기 <b>전일</b>까지 산입 — 만기일 당일에는 납입 회차가 없다</li>
 * </ul>
 *
 * <p><b>라운딩</b>: 모든 금액은 {@link CommissionConstants#AMOUNT_SCALE} 절사(DOWN) —
 * 환수 계산({@link ClawbackCalculator})과 같은 정책이다.
 *
 * <p>산출 결과는 {@link PayoutQuote} 로 근거(기납입합계·적용요율·경과월수·납입회차수)까지
 * 함께 반환한다 — D-G5 산출근거 스냅샷의 원천.
 */
public final class GeneralPayoutCalculator {

    private GeneralPayoutCalculator() {
        // static utility
    }

    /**
     * 산출 결과 + 근거 스냅샷 (D-G5).
     *
     * @param amount           지급액 (통화 최소단위 절사, >= 0 — 0 이면 호출자가 payout 미생성)
     * @param paidPremiumTotal 기납입보험료 합계 (정상납입 가정)
     * @param appliedRate      적용 환급률 (스케일 {@link GeneralPayoutConstants#RATE_SCALE})
     * @param elapsedMonths    효력일부터 기준일까지 완료된 개월 수
     * @param installmentCount 납입 회차 수
     */
    public record PayoutQuote(BigDecimal amount, BigDecimal paidPremiumTotal,
                              BigDecimal appliedRate, long elapsedMonths, int installmentCount) {
    }

    /**
     * 해약환급금 (D-G3) — 기납입보험료 × 경과월수 구간 환급률.
     *
     * @param annualPremium      연 보험료
     * @param paymentCycleMonths 납입주기(개월) — 12를 나누는 값만 허용
     * @param effectiveDate      계약 효력일
     * @param endDate            기준일 — 해지일(ACTIVE→SURRENDERED) 또는 실효일(LAPSED→EXPIRED)
     * @throws InvalidGeneralPayoutException 입력이 유효하지 않으면
     */
    public static PayoutQuote surrenderRefund(BigDecimal annualPremium, int paymentCycleMonths,
                                              LocalDate effectiveDate, LocalDate endDate) {
        validate(annualPremium, paymentCycleMonths, effectiveDate, endDate);
        long elapsedMonths = ChronoUnit.MONTHS.between(effectiveDate, endDate);
        BigDecimal rate = surrenderRateFor(elapsedMonths);
        int installments = installmentsPaid(elapsedMonths, paymentCycleMonths);
        BigDecimal paidTotal = paidPremiumTotal(annualPremium, paymentCycleMonths, installments);
        BigDecimal amount = paidTotal.multiply(rate)
                .setScale(CommissionConstants.AMOUNT_SCALE, RoundingMode.DOWN);
        return new PayoutQuote(amount, paidTotal, rate, elapsedMonths, installments);
    }

    /**
     * 만기보험금 (D-G3) — 기납입보험료 × {@link GeneralPayoutConstants#MATURITY_REFUND_RATE}.
     *
     * <p>납입 회차는 만기 <b>전일</b>까지 산입한다 — 만기일 당일에는 회차가 없다.
     *
     * @param maturityDate 만기일 — 효력일보다 뒤여야 한다
     * @throws InvalidGeneralPayoutException 입력이 유효하지 않으면
     */
    public static PayoutQuote maturityBenefit(BigDecimal annualPremium, int paymentCycleMonths,
                                              LocalDate effectiveDate, LocalDate maturityDate) {
        Objects.requireNonNull(maturityDate, "maturityDate");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        if (!maturityDate.isAfter(effectiveDate)) {
            throw new InvalidGeneralPayoutException(
                    "만기일은 효력일보다 뒤여야 합니다: effective=" + effectiveDate + ", maturity=" + maturityDate);
        }
        // 만기일 당일에는 납입 회차가 없다 — 전일까지 산입 (D-G2)
        LocalDate lastPayableDate = maturityDate.minusDays(1);
        validate(annualPremium, paymentCycleMonths, effectiveDate, lastPayableDate);
        long payableMonths = ChronoUnit.MONTHS.between(effectiveDate, lastPayableDate);
        int installments = installmentsPaid(payableMonths, paymentCycleMonths);
        BigDecimal paidTotal = paidPremiumTotal(annualPremium, paymentCycleMonths, installments);
        BigDecimal rate = GeneralPayoutConstants.MATURITY_REFUND_RATE
                .setScale(GeneralPayoutConstants.RATE_SCALE, RoundingMode.DOWN);
        BigDecimal amount = paidTotal.multiply(rate)
                .setScale(CommissionConstants.AMOUNT_SCALE, RoundingMode.DOWN);
        long elapsedMonths = ChronoUnit.MONTHS.between(effectiveDate, maturityDate);
        return new PayoutQuote(amount, paidTotal, rate, elapsedMonths, installments);
    }

    /**
     * 철회환급금 — 청약철회 시 기납입보험료 전액 (D6 수수료 전액 환수와 대칭).
     *
     * @param endDate 기준일 — 철회일(ACTIVE) 또는 실효일(LAPSED, 실효 이후 납입 없음)
     * @throws InvalidGeneralPayoutException 입력이 유효하지 않으면
     */
    public static PayoutQuote withdrawalRefund(BigDecimal annualPremium, int paymentCycleMonths,
                                               LocalDate effectiveDate, LocalDate endDate) {
        validate(annualPremium, paymentCycleMonths, effectiveDate, endDate);
        long elapsedMonths = ChronoUnit.MONTHS.between(effectiveDate, endDate);
        int installments = installmentsPaid(elapsedMonths, paymentCycleMonths);
        BigDecimal paidTotal = paidPremiumTotal(annualPremium, paymentCycleMonths, installments);
        BigDecimal rate = BigDecimal.ONE.setScale(GeneralPayoutConstants.RATE_SCALE, RoundingMode.DOWN);
        return new PayoutQuote(paidTotal, paidTotal, rate, elapsedMonths, installments);
    }

    // ────────────────────────────────────────────────────────────────────
    // 내부 산출 (D-G2)
    // ────────────────────────────────────────────────────────────────────

    /** 납입 회차 수 = floor(경과월수 / 납입주기) + 1 — 기준일 도래분 포함, 1회차는 효력일 당일. */
    private static int installmentsPaid(long elapsedMonths, int paymentCycleMonths) {
        return (int) (elapsedMonths / paymentCycleMonths) + 1;
    }

    /** 기납입 합계 = (연 보험료 × 주기/12, 절사) × 회차 수 — 정상납입 가정. */
    private static BigDecimal paidPremiumTotal(BigDecimal annualPremium, int paymentCycleMonths,
                                               int installments) {
        BigDecimal perInstallment = annualPremium
                .multiply(BigDecimal.valueOf(paymentCycleMonths))
                .divide(BigDecimal.valueOf(GeneralPayoutConstants.MONTHS_PER_YEAR),
                        CommissionConstants.AMOUNT_SCALE, RoundingMode.DOWN);
        return perInstallment.multiply(BigDecimal.valueOf(installments))
                .setScale(CommissionConstants.AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /** D-G3: 경과월수 구간의 환급률 — floor-entry (하한 포함, 상한 미포함). */
    private static BigDecimal surrenderRateFor(long elapsedMonths) {
        int key = (int) Math.min(elapsedMonths, Integer.MAX_VALUE);
        return GeneralPayoutConstants.SURRENDER_REFUND_RATE_TIERS.floorEntry(key).getValue()
                .setScale(GeneralPayoutConstants.RATE_SCALE, RoundingMode.DOWN);
    }

    private static void validate(BigDecimal annualPremium, int paymentCycleMonths,
                                 LocalDate effectiveDate, LocalDate endDate) {
        Objects.requireNonNull(annualPremium, "annualPremium");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(endDate, "endDate");
        if (annualPremium.signum() < 0) {
            throw new InvalidGeneralPayoutException(
                    "연 보험료는 음수일 수 없습니다: " + annualPremium.toPlainString());
        }
        if (paymentCycleMonths <= 0
                || GeneralPayoutConstants.MONTHS_PER_YEAR % paymentCycleMonths != 0) {
            throw new InvalidGeneralPayoutException(
                    "납입주기는 " + GeneralPayoutConstants.MONTHS_PER_YEAR
                            + "를 나누는 양수 개월이어야 합니다: " + paymentCycleMonths);
        }
        if (endDate.isBefore(effectiveDate)) {
            throw new InvalidGeneralPayoutException(
                    "기준일이 효력일보다 빠릅니다: effective=" + effectiveDate + ", end=" + endDate);
        }
    }
}
