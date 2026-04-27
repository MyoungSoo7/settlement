package github.lms.lemuel.report.domain;

import java.math.BigDecimal;

/**
 * 단일 대사 불변식의 검증 결과.
 *
 * <p>예: {@code "payments_minus_refunds_equals_settlement"} 는
 * {@code Σ(payments.amount - payments.refunded_amount) == Σ(settlements.net_amount + settlements.commission)}
 * 규칙이 성립하는지 확인한다.
 */
public record ReconciliationCheck(
        String name,
        boolean passed,
        BigDecimal expected,
        BigDecimal actual,
        BigDecimal discrepancy,
        String detail
) {
    public static ReconciliationCheck of(String name, BigDecimal expected, BigDecimal actual, String detail) {
        BigDecimal e = expected != null ? expected : BigDecimal.ZERO;
        BigDecimal a = actual != null ? actual : BigDecimal.ZERO;
        BigDecimal diff = e.subtract(a);
        boolean passed = diff.compareTo(BigDecimal.ZERO) == 0;
        return new ReconciliationCheck(name, passed, e, a, diff, detail);
    }
}
