package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 리스·할부 스케줄(리스료 산정표). 취득원가·선수금·보증금·잔존가치·기간·이율을 받아 회차별 납입 내역을
 * 산정하는 결정적 · 순수 계산(프레임워크 의존 0). 대출 상환표({@link RepaymentSchedule})의 자매편이다.
 *
 * <h4>대출과 갈리는 지점 — 잔존가치</h4>
 * 대출은 원금을 0 까지 갚아 끝내지만, 리스는 <b>만기 시점 물건 값(잔존가치)을 남긴 채</b> 끝난다.
 * 그래서 회수 대상은 물건 값 전부가 아니라 {@code 리스원금 − 잔존가치의 현재가치} 다. 잔존가치를 크게 잡을수록
 * 월 리스료가 싸지는 대신 만기 인수가·반환 물건 처분 위험이 커진다 — 그 균형이 리스 상품의 본질이다.
 *
 * <h4>계산 규칙(money-safety 준수)</h4>
 * <ul>
 *   <li>리스 원금 = {@code 취득원가 − 선수금 − 보증금}. 선수금은 돌려주지 않는 선납, 보증금은 만기 반환
 *       담보금이지만 <b>둘 다 회수 대상에서 빠진다</b>는 점은 같다.</li>
 *   <li>월 리스료 = {@code (리스원금 − 잔존가치 현가) × 연금계수}. 이율 0 이면 회수 원금을 기간으로 나눈다.</li>
 *   <li>중간 계산은 고정밀({@code DECIMAL128}), 절사는 회차 금액 산정 시점에만({@link RoundingPolicy}).</li>
 *   <li><b>마지막 회차가 잔여를 흡수</b>해 만기 잔액이 잔존가치와 <b>정확히</b> 일치한다. 여기가 어긋나면
 *       만기 인수가·반환 정산이 계약서와 달라진다.</li>
 *   <li>계약 금액은 조용히 보정하지 않는다 — 원 단위로 표현되지 않으면 반올림 대신 거부한다.</li>
 * </ul>
 *
 * @param type              상품 종류(금융리스·운용리스·할부)
 * @param acquisitionCost   취득원가(물건 값)
 * @param downPayment       선수금(계약 시 선납, 반환 없음)
 * @param deposit           보증금(계약 시 예치, 만기 반환)
 * @param residualValue     잔존가치(만기 인수가 또는 반환 물건 장부가)
 * @param termMonths        리스 기간(개월, ≥1)
 * @param annualRatePercent 연이율(%)
 * @param roundingPolicy    산정에 쓰인 금액 반올림 정책
 * @param monthlyRate       월이율(소수, 참고용 10자리)
 * @param financedAmount    리스 원금(= 취득원가 − 선수금 − 보증금)
 * @param monthlyRental     월 리스료(마지막 회차는 잔여 흡수로 다를 수 있다)
 * @param installments      회차별 납입 내역(불변)
 * @param totalRental       총 납입액 합계
 * @param totalInterest     이자 합계
 */
public record LeaseSchedule(
        AssetFinanceType type,
        BigDecimal acquisitionCost,
        BigDecimal downPayment,
        BigDecimal deposit,
        BigDecimal residualValue,
        int termMonths,
        BigDecimal annualRatePercent,
        RoundingPolicy roundingPolicy,
        BigDecimal monthlyRate,
        BigDecimal financedAmount,
        BigDecimal monthlyRental,
        List<LeaseInstallment> installments,
        BigDecimal totalRental,
        BigDecimal totalInterest) {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    public LeaseSchedule {
        installments = List.copyOf(installments);
    }

    /** 원화 기준으로 리스·할부 스케줄을 산정한다. */
    public static LeaseSchedule of(AssetFinanceType type, BigDecimal acquisitionCost, BigDecimal downPayment,
                                   BigDecimal deposit, BigDecimal residualValue, int termMonths,
                                   BigDecimal annualRatePercent) {
        return of(type, acquisitionCost, downPayment, deposit, residualValue, termMonths, annualRatePercent,
                RoundingPolicy.KRW);
    }

    /**
     * 반올림 정책을 지정해 리스·할부 스케줄을 산정한다.
     *
     * @throws LoanInvariantViolationException 금액이 정책 단위로 표현되지 않거나, 상품별 잔존가치 규칙을
     *                                         어기거나, 회수할 리스 원금이 남지 않는 경우
     */
    public static LeaseSchedule of(AssetFinanceType type, BigDecimal acquisitionCost, BigDecimal downPayment,
                                   BigDecimal deposit, BigDecimal residualValue, int termMonths,
                                   BigDecimal annualRatePercent, RoundingPolicy rounding) {
        if (type == null) throw new LoanInvariantViolationException("상품 종류는 필수입니다");
        if (rounding == null) throw new LoanInvariantViolationException("반올림 정책은 필수입니다");
        if (termMonths < 1) {
            throw new LoanInvariantViolationException("리스 기간은 1개월 이상이어야 합니다: " + termMonths);
        }
        if (annualRatePercent == null || annualRatePercent.signum() < 0) {
            throw new LoanInvariantViolationException("연이율은 음수일 수 없습니다: " + annualRatePercent);
        }

        BigDecimal cost = requireExactNonNegative(acquisitionCost, "취득원가", rounding);
        BigDecimal down = requireExactNonNegative(downPayment, "선수금", rounding);
        BigDecimal guarantee = requireExactNonNegative(deposit, "보증금", rounding);
        BigDecimal residual = requireExactNonNegative(residualValue, "잔존가치", rounding);
        if (cost.signum() <= 0) {
            throw new LoanInvariantViolationException("취득원가는 0보다 커야 합니다: " + acquisitionCost);
        }
        validateResidualForType(type, residual);

        BigDecimal financed = cost.subtract(down).subtract(guarantee);
        if (financed.signum() <= 0) {
            throw new LoanInvariantViolationException(
                    "리스 원금이 남지 않습니다 — 선수금+보증금이 취득원가 이상입니다: "
                            + down + " + " + guarantee + " ≥ " + cost);
        }
        if (residual.compareTo(financed) >= 0) {
            throw new LoanInvariantViolationException(
                    "잔존가치는 리스 원금보다 작아야 합니다(회수할 원금이 없습니다): " + residual + " ≥ " + financed);
        }

        BigDecimal monthlyRate = annualRatePercent.divide(HUNDRED, MC).divide(MONTHS_PER_YEAR, MC);
        BigDecimal rental = fixedRental(financed, residual, termMonths, monthlyRate, rounding);
        List<LeaseInstallment> installments = schedule(financed, residual, termMonths, monthlyRate, rental, rounding);

        BigDecimal totalRental = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        for (LeaseInstallment installment : installments) {
            totalRental = totalRental.add(installment.rental());
            totalInterest = totalInterest.add(installment.interest());
        }

        return new LeaseSchedule(type, cost, down, guarantee, residual, termMonths, annualRatePercent, rounding,
                monthlyRate.setScale(10, RoundingMode.HALF_UP), financed, rental, installments,
                rounding.round(totalRental), rounding.round(totalInterest));
    }

    /**
     * 월 리스료 — 회수 대상은 리스 원금 전부가 아니라 <b>잔존가치의 현재가치를 뺀 나머지</b>다.
     * 잔존가치는 만기에 한 번에 회수되므로 지금 값으로 환산해 빼야 회차 금액이 맞는다.
     */
    private static BigDecimal fixedRental(BigDecimal financed, BigDecimal residual, int n,
                                          BigDecimal monthlyRate, RoundingPolicy rounding) {
        if (monthlyRate.signum() == 0) {
            return rounding.round(financed.subtract(residual).divide(new BigDecimal(n), MC));
        }
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(n, MC);          // (1+i)^n
        BigDecimal residualPresentValue = residual.divide(growth, MC);           // 잔존가치 현가
        BigDecimal recoverable = financed.subtract(residualPresentValue);
        BigDecimal annuityFactor = monthlyRate.multiply(growth).divide(growth.subtract(BigDecimal.ONE), MC);
        return rounding.round(recoverable.multiply(annuityFactor));
    }

    /** 회차 산정 — 마지막 회차가 잔여를 흡수해 만기 잔액이 잔존가치와 정확히 일치한다. */
    private static List<LeaseInstallment> schedule(BigDecimal financed, BigDecimal residual, int n,
                                                   BigDecimal monthlyRate, BigDecimal rental,
                                                   RoundingPolicy rounding) {
        List<LeaseInstallment> rows = new ArrayList<>(n);
        BigDecimal balance = financed;
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = rounding.round(balance.multiply(monthlyRate));
            BigDecimal principalPortion;
            BigDecimal payment;
            if (k == n) {
                principalPortion = balance.subtract(residual);   // 만기 잔액 = 잔존가치
                payment = principalPortion.add(interest);
            } else {
                principalPortion = rental.subtract(interest);
                payment = rental;
            }
            balance = balance.subtract(principalPortion);
            rows.add(new LeaseInstallment(k, rounding.round(principalPortion), interest,
                    rounding.round(payment), rounding.round(balance)));
        }
        return rows;
    }

    private static void validateResidualForType(AssetFinanceType type, BigDecimal residual) {
        if (!type.allowsResidualValue() && residual.signum() > 0) {
            throw new LoanInvariantViolationException(
                    type.label() + "은(는) 물건 값을 전액 회수하는 상품이라 잔존가치를 둘 수 없습니다: " + residual);
        }
        if (type.requiresResidualValue() && residual.signum() <= 0) {
            throw new LoanInvariantViolationException(
                    type.label() + "은(는) 만기 반환 전제라 잔존가치가 필요합니다(0 은 모순)");
        }
    }

    private static BigDecimal requireExactNonNegative(BigDecimal value, String label, RoundingPolicy rounding) {
        if (value == null) throw new LoanInvariantViolationException(label + "은(는) 필수입니다");
        if (value.signum() < 0) {
            throw new LoanInvariantViolationException(label + "은(는) 음수일 수 없습니다: " + value);
        }
        if (!rounding.isExact(value)) {
            throw new LoanInvariantViolationException(
                    label + "은(는) " + rounding.unitDescription() + " 금액이어야 합니다(자동 보정 안 함): " + value);
        }
        return rounding.round(value);
    }

    /** 특정 회차 시점의 잔액 — 중도해지 정산의 기준이 된다. 0 회차는 리스 원금. */
    public BigDecimal balanceAfter(int installmentNo) {
        if (installmentNo < 0 || installmentNo > termMonths) {
            throw new LoanInvariantViolationException(
                    "회차는 0~" + termMonths + " 범위여야 합니다: " + installmentNo);
        }
        return installmentNo == 0 ? financedAmount : installments.get(installmentNo - 1).remainingBalance();
    }
}
