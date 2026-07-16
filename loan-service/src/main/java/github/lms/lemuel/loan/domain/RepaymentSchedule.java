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
 *   <li>전 금액은 <b>원(KRW) 단위 정수 스케일 {@link BigDecimal}</b> — 회차별 이자·납입액은 산정 시점에
 *       {@code setScale(0, HALF_UP)} 로 원 단위 반올림한다.</li>
 *   <li>이자는 <b>직전 회차 상환 후 남은 잔액 × 월이율</b>(월이율 = 연이율/12). 잔액 기준 후취.</li>
 *   <li><b>마지막 회차가 잔여 원금을 흡수</b>해 원금 합계가 신청 원금과 정확히 일치하도록 라운딩 오차를 정산한다.</li>
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
     * 상환 스케줄을 산정한다.
     *
     * @throws LoanInvariantViolationException 원금 ≤ 0, 기간 &lt; 1, 연이율 &lt; 0, 방식 null 인 경우
     */
    public static RepaymentSchedule of(BigDecimal principal, int termMonths,
                                       BigDecimal annualRatePercent, RepaymentMethod method) {
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

        BigDecimal principalWon = won(principal);
        BigDecimal monthlyRate = annualRatePercent.divide(HUNDRED, MC).divide(MONTHS_PER_YEAR, MC);

        List<RepaymentInstallment> installments = switch (method) {
            case BULLET -> bullet(principalWon, termMonths, monthlyRate);
            case EQUAL_PRINCIPAL -> equalPrincipal(principalWon, termMonths, monthlyRate);
            case EQUAL_PAYMENT -> equalPayment(principalWon, termMonths, monthlyRate);
        };

        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalPayment = BigDecimal.ZERO;
        for (RepaymentInstallment it : installments) {
            totalInterest = totalInterest.add(it.interest());
            totalPayment = totalPayment.add(it.payment());
        }

        return new RepaymentSchedule(principalWon, termMonths, annualRatePercent, method,
                monthlyRate.setScale(10, RoundingMode.HALF_UP), installments,
                principalWon, won(totalInterest), won(totalPayment));
    }

    // ─── 방식별 회차 산정 ────────────────────────────────────────────────────────

    /** 만기일시상환: 1~n-1 이자만, n 회차 원금 전액 + 이자. */
    private static List<RepaymentInstallment> bullet(BigDecimal principal, int n, BigDecimal monthlyRate) {
        BigDecimal monthlyInterest = won(principal.multiply(monthlyRate));
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        for (int k = 1; k <= n; k++) {
            boolean last = k == n;
            BigDecimal principalPortion = last ? principal : BigDecimal.ZERO;
            BigDecimal remaining = last ? BigDecimal.ZERO : principal;
            rows.add(new RepaymentInstallment(k, scale0(principalPortion), monthlyInterest,
                    scale0(principalPortion.add(monthlyInterest)), scale0(remaining)));
        }
        return rows;
    }

    /** 원금균등상환: 매기 원금 P/n 동일(마지막이 잔여 흡수), 이자는 잔액 기준 체감. */
    private static List<RepaymentInstallment> equalPrincipal(BigDecimal principal, int n, BigDecimal monthlyRate) {
        BigDecimal basePrincipal = won(principal.divide(new BigDecimal(n), MC));
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        BigDecimal remaining = principal;
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = won(remaining.multiply(monthlyRate));
            BigDecimal principalPortion = (k == n) ? remaining : basePrincipal;
            remaining = remaining.subtract(principalPortion);
            rows.add(new RepaymentInstallment(k, scale0(principalPortion), interest,
                    scale0(principalPortion.add(interest)), scale0(remaining)));
        }
        return rows;
    }

    /** 원리금균등상환: 연금 공식으로 고정 납입액 산정, 마지막이 잔여 원금 흡수. */
    private static List<RepaymentInstallment> equalPayment(BigDecimal principal, int n, BigDecimal monthlyRate) {
        BigDecimal fixedPayment;
        if (monthlyRate.signum() == 0) {
            fixedPayment = won(principal.divide(new BigDecimal(n), MC));
        } else {
            BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(n, MC); // (1+i)^n
            fixedPayment = won(principal.multiply(monthlyRate).multiply(factor)
                    .divide(factor.subtract(BigDecimal.ONE), MC));
        }
        List<RepaymentInstallment> rows = new ArrayList<>(n);
        BigDecimal remaining = principal;
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = won(remaining.multiply(monthlyRate));
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
            rows.add(new RepaymentInstallment(k, scale0(principalPortion), interest,
                    scale0(payment), scale0(remaining)));
        }
        return rows;
    }

    // ─── 원 단위 반올림 헬퍼 ──────────────────────────────────────────────────────

    /** 원(KRW) 단위 정수 반올림(HALF_UP). */
    private static BigDecimal won(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    /** 이미 원 단위인 값의 스케일 정규화(음수 방지 아님 — 표시 일관성용). */
    private static BigDecimal scale0(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }
}
