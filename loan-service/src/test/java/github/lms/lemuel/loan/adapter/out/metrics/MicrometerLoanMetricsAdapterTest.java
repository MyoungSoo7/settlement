package github.lms.lemuel.loan.adapter.out.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MicrometerLoanMetricsAdapter} 가 각 액션을 올바른 Micrometer 카운터에 적재하는지 검증한다.
 */
class MicrometerLoanMetricsAdapterTest {

    private SimpleMeterRegistry registry;
    private MicrometerLoanMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        adapter = new MicrometerLoanMetricsAdapter(registry);
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void 신청_실행_거절_카운터가_증가한다() {
        adapter.advanceRequested();
        adapter.advanceDisbursed();
        adapter.advanceRejected();
        adapter.corporateRequested();
        adapter.corporateRejected();
        adapter.corporateDisbursed();

        assertThat(count("loan.advance.requested")).isEqualTo(1.0);
        assertThat(count("loan.advance.disbursed")).isEqualTo(1.0);
        assertThat(count("loan.advance.rejected")).isEqualTo(1.0);
        assertThat(count("loan.corporate.requested")).isEqualTo(1.0);
        assertThat(count("loan.corporate.rejected")).isEqualTo(1.0);
        assertThat(count("loan.corporate.disbursed")).isEqualTo(1.0);
    }

    @Test
    void 상환은_건수와_금액을_함께_적재한다() {
        adapter.repaymentApplied(new BigDecimal("800000.00"));

        assertThat(count("loan.repayment.applied")).isEqualTo(1.0);
        assertThat(count("loan.repayment.amount")).isEqualTo(800000.0);
    }

    @Test
    void 차감0_상환은_건수만_올리고_금액은_올리지_않는다() {
        adapter.repaymentApplied(BigDecimal.ZERO);
        adapter.repaymentApplied(null);

        assertThat(count("loan.repayment.applied")).isEqualTo(2.0);
        assertThat(count("loan.repayment.amount")).isEqualTo(0.0);
    }

    @Test
    void 카운터는_이벤트_이전에도_0으로_사전등록된다() {
        assertThat(count("loan.advance.requested")).isEqualTo(0.0);
        assertThat(count("loan.repayment.amount")).isEqualTo(0.0);
    }
}
