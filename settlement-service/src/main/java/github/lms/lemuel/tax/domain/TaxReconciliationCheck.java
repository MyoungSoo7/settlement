package github.lms.lemuel.tax.domain;

import java.math.BigDecimal;

/**
 * 세무 3자 대사 개별 항목 — 하나의 항등식 검증 결과(기대값·실제값·차이).
 *
 * @param name     항등식 이름(예: "부가세=세금계산서세액=원장VAT예수")
 * @param expected 기대값
 * @param actual   실제값
 * @param passed   기대==실제 여부
 */
public record TaxReconciliationCheck(String name, BigDecimal expected, BigDecimal actual, boolean passed) {

    public static TaxReconciliationCheck of(String name, BigDecimal expected, BigDecimal actual) {
        boolean ok = expected.compareTo(actual) == 0;
        return new TaxReconciliationCheck(name, expected, actual, ok);
    }

    /** 기대−실제 차이(부호 유지). */
    public BigDecimal discrepancy() {
        return actual.subtract(expected);
    }
}
