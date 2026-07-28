package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 대출 상환 스케줄(원리금 상환표). 원금·기간(개월)·연이자율·상환방식을 받아 회차별 납입 내역을 산정하는
 * 결정적 · 순수 계산(프레임워크 의존 0). {@link CorporateCreditPolicy} 와 같은 밴드/정책류 도메인 계산의
 * 자매편으로, 경계값 단위테스트로 회귀를 차단한다.
 *
 * <h4>계산 규칙(money-safety 준수)</h4>
 * <ul>
 *   <li>회차별 금액의 절사 단위는 주입받은 {@link RoundingPolicy} 가 정한다(기본 {@link RoundingPolicy#KRW}
 *       = 원 단위 정수 HALF_UP). 중간 계산은 고정밀({@code DECIMAL128})로 하고 <b>절사는 회차 금액 산정
 *       시점에만</b> 적용한다.</li>
 *   <li>이자는 <b>직전 회차 상환 후 남은 잔액 × 월이율</b>(월이율 = 연이율/12). 잔액 기준 후취.</li>
 *   <li><b>마지막 회차가 잔여 원금을 흡수</b>해 원금 합계가 신청 원금과 정확히 일치하도록 라운딩 오차를 정산한다.</li>
 *   <li><b>계약 원금은 조용히 보정하지 않는다</b> — 정책 단위로 정확히 표현되지 않는 원금(원화에서 {@code 1000000.5})은
 *       반올림하지 않고 거부한다({@link RoundingPolicy#isExact}). 사용자가 입력한 계약 금액을 시스템이 임의로
 *       바꾸면 상환표 총액과 계약서가 어긋나기 때문이다.</li>
 * </ul>
 *
 * <h4>상환방식</h4>
 * <ul>
 *   <li>만기일시(BULLET): 1~n-1 회차는 이자만, n 회차에 원금 전액 + 이자.</li>
 *   <li>원리금균등(EQUAL_PAYMENT): 매기 납입액(원금+이자) 동일 — 연금(annuity) 공식 {@code P·i·(1+i)^n / ((1+i)^n − 1)}.</li>
 *   <li>원금균등(EQUAL_PRINCIPAL): 매기 납입 원금 {@code P/n} 동일, 잔액 감소로 이자 체감.</li>
 * </ul>
 *
 * @param principal          신청 원금(원)
 * @param termMonths         기간(개월, ≥1)
 * @param annualRatePercent  연이자율(%) — 예: 5.5 는 연 5.5%
 * @param method             상환방식
 * @param roundingPolicy     산정에 쓰인 금액 반올림 정책
 * @param monthlyInterestRate 월이율(소수) — 산정에 쓰인 값(참고용, 소수 10자리)
 * @param installments       회차별 상환 내역(불변)
 * @param totalPrincipal     원금 합계(= 신청 원금)
 * @param totalInterest      이자 합계
 * @param totalPayment       총 납입액 합계(= 원금 + 이자)
 */
public record RepaymentSchedule(
        BigDecimal principal,
        int termMonths,
        BigDecimal annualRatePercent,
        RepaymentMethod method,
        RoundingPolicy roundingPolicy,
        BigDecimal monthlyInterestRate,
        List<RepaymentInstallment> installments,
        BigDecimal totalPrincipal,
        BigDecimal totalInterest,
        BigDecimal totalPayment) {

    /** 내부 고정밀 연산 컨텍스트 — 최종 원 단위 반올림 전까지의 이율/연금계수 계산용. */
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    /** 방어적 복사 + 스케일 정규화. */
    public RepaymentSchedule {
        installments = List.copyOf(installments);
    }

    /**
     * 원화({@link RoundingPolicy#KRW}) 기준으로 상환 스케줄을 산정한다.
     *
     * @throws LoanInvariantViolationException 원금 ≤ 0 · 원 단위 미만 소수 포함, 기간 &lt; 1,
     *                                         연이율 &lt; 0, 방식 null 인 경우
     */
    public static RepaymentSchedule of(BigDecimal principal, int termMonths,
                                       BigDecimal annualRatePercent, RepaymentMethod method) {
        return of(principal, termMonths, annualRatePercent, method, RoundingPolicy.KRW);
    }

    /**
     * 반올림 정책을 지정해 상환 스케줄을 산정한다(통화·상품별 절사 단위 차이 수용).
     *
     * @throws LoanInvariantViolationException 원금 ≤ 0 · 정책 단위로 표현 불가, 기간 &lt; 1,
     *                                         연이율 &lt; 0, 방식/정책 null 인 경우
     */
    public static RepaymentSchedule of(BigDecimal principal, int termMonths,
                                       BigDecimal annualRatePercent, RepaymentMethod method,
                                       RoundingPolicy rounding) {
        if (principal == null || principal.signum() <= 0) {
            throw new LoanInvariantViolationException("대출 원금은 양수여야 합니다: " + principal);
        }
        if (termMonths < 1) {
            throw new LoanInvariantViolationException("대출 기간(개월)은 1 이상이어야 합니다: " + termMonths);
        }
        if (annualRatePercent == null || annualRatePercent.signum() < 0) {
            throw new LoanInvariantViolationException("연이자율은 음수일 수 없습니다: " + annualRatePercent);
        }
        if (method == null) {
            throw new LoanInvariantViolationException("상환방식은 필수입니다");
        }
        if (rounding == null) {
            throw new LoanInvariantViolationException("반올림 정책은 필수입니다");
        }
        // 계약 원금은 조용히 보정하지 않는다 — 정책 단위로 표현 불가하면 반올림 대신 거부.
        if (!rounding.isExact(principal)) {
            throw new LoanInvariantViolationException(
                    "대출 원금은 " + rounding.unitDescription() + " 금액이어야 합니다(자동 보정 안 함): " + principal);
        }

        BigDecimal principalAmount = rounding.round(principal);
        BigDecimal monthlyRate = annualRatePercent.divide(HUNDRED, MC).divide(MONTHS_PER_YEAR, MC);

        List<RepaymentInstallment> installments = switch (method) {
            case BULLET -> bullet(principalAmount, termMonths, monthlyRate, rounding);
            case EQUAL_PRINCIPAL -> equalPrincipal(principalAmount, termMonths, monthlyRate, rounding);
            case EQUAL_PAYMENT -> equalPayment(principalAmount, termMonths, monthlyRate, rounding);
        };

        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalPayment = BigDecimal.ZERO;
        for (RepaymentInstallment it : installments) {
            totalInterest = totalInterest.add(it.interest());
            totalPayment = totalPayment.add(it.payment());
        }

        return new RepaymentSchedule(principalAmount, termMonths, annualRatePercent, method, rounding,
                monthlyRate.setScale(10, RoundingMode.HALF_UP), installments,
                principalAmount, rounding.round(totalInterest), rounding.round(totalPayment));
    }

    // ─── 방식별 회차 산정 ────────────────────────────────────────────────────────

    /** 만기일시상환: 1~n-1 이자만, n 회차 원금 전액 + 이자. */
    private static List<RepaymentInstallment> bullet(BigDecimal principal, int n, BigDecimal monthlyRate,
                                                     RoundingPolicy rounding) {
        BigDecimal monthlyInterest = rounding.round(principal.multiply(monthlyRate));
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        for (int k = 1; k <= n; k++) {
            boolean last = k == n;
            BigDecimal principalPortion = last ? principal : BigDecimal.ZERO;
            BigDecimal remaining = last ? BigDecimal.ZERO : principal;
            rows.add(new RepaymentInstallment(k, rounding.round(principalPortion), monthlyInterest,
                    rounding.round(principalPortion.add(monthlyInterest)), rounding.round(remaining)));
        }
        return rows;
    }

    /** 원금균등상환: 매기 원금 P/n 동일(마지막이 잔여 흡수), 이자는 잔액 기준 체감. */
    private static List<RepaymentInstallment> equalPrincipal(BigDecimal principal, int n, BigDecimal monthlyRate,
                                                             RoundingPolicy rounding) {
        BigDecimal basePrincipal = rounding.round(principal.divide(new BigDecimal(n), MC));
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        BigDecimal remaining = principal;
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = rounding.round(remaining.multiply(monthlyRate));
            BigDecimal principalPortion = (k == n) ? remaining : basePrincipal;
            remaining = remaining.subtract(principalPortion);
            rows.add(new RepaymentInstallment(k, rounding.round(principalPortion), interest,
                    rounding.round(principalPortion.add(interest)), rounding.round(remaining)));
        }
        return rows;
    }

    /** 원리금균등상환: 연금 공식으로 고정 납입액 산정, 마지막이 잔여 원금 흡수. */
    private static List<RepaymentInstallment> equalPayment(BigDecimal principal, int n, BigDecimal monthlyRate,
                                                           RoundingPolicy rounding) {
        BigDecimal fixedPayment;
        if (monthlyRate.signum() == 0) {
            fixedPayment = rounding.round(principal.divide(new BigDecimal(n), MC));
        } else {
            BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(n, MC); // (1+i)^n
            fixedPayment = rounding.round(principal.multiply(monthlyRate).multiply(factor)
                    .divide(factor.subtract(BigDecimal.ONE), MC));
        }
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        BigDecimal remaining = principal;
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = rounding.round(remaining.multiply(monthlyRate));
            BigDecimal principalPortion;
            BigDecimal payment;
            if (k == n) {
                principalPortion = remaining;        // 마지막 회차 잔여 흡수
                payment = principalPortion.add(interest);
            } else {
                principalPortion = fixedPayment.subtract(interest);
                payment = fixedPayment;
            }
            remaining = remaining.subtract(principalPortion);
            rows.add(new RepaymentInstallment(k, rounding.round(principalPortion), interest,
                    rounding.round(payment), rounding.round(remaining)));
        }
        return rows;
    }
}
