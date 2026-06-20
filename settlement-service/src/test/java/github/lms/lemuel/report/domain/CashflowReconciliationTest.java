package github.lms.lemuel.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CashflowReconciliationTest {

    @Test
    @DisplayName("빈 체크 리스트 — matched=true, checksRun=0")
    void emptyChecks() {
        CashflowReconciliation r = CashflowReconciliation.of(List.of());
        assertThat(r.matched()).isTrue();
        assertThat(r.checksRun()).isZero();
        assertThat(r.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("모든 체크 통과 — matched=true, mismatches 비어있음")
    void allChecksPass() {
        ReconciliationCheck ok = ReconciliationCheck.of("inv1",
                new BigDecimal("100"), new BigDecimal("100"), "ok");

        CashflowReconciliation r = CashflowReconciliation.of(List.of(ok));

        assertThat(r.matched()).isTrue();
        assertThat(r.checksRun()).isEqualTo(1);
        assertThat(r.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("한 체크라도 실패하면 matched=false + mismatches 에 포함")
    void anyCheckFailsMeansNotMatched() {
        ReconciliationCheck ok = ReconciliationCheck.of("inv1",
                new BigDecimal("100"), new BigDecimal("100"), "ok");
        ReconciliationCheck bad = ReconciliationCheck.of("inv2",
                new BigDecimal("100"), new BigDecimal("99"), "diff=1");

        CashflowReconciliation r = CashflowReconciliation.of(List.of(ok, bad));

        assertThat(r.matched()).isFalse();
        assertThat(r.checksRun()).isEqualTo(2);
        assertThat(r.mismatches()).hasSize(1);
        assertThat(r.mismatches().get(0).name()).isEqualTo("inv2");
        assertThat(r.mismatches().get(0).discrepancy()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("ReconciliationCheck — expected/actual null 을 0 으로 방어")
    void checkHandlesNulls() {
        ReconciliationCheck c = ReconciliationCheck.of("inv", null, null, "");

        assertThat(c.passed()).isTrue();
        assertThat(c.expected()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(c.actual()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(c.discrepancy()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
