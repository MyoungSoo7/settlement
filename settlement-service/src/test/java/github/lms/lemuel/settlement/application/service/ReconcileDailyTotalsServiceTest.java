package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcileDailyTotalsServiceTest {

    @Mock LoadDailyTotalsPort loadDailyTotalsPort;
    @InjectMocks ReconcileDailyTotalsService service;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 22);

    @Test @DisplayName("일치: 캡처 gross=정산 gross, 반영 환불=정산 refunded → matched=true")
    void matched() {
        // 캡처 축: order gross 60,000 == settlement payment_amount 60,000
        // 환불 축: order 반영 환불 40,000 == settlement refunded_amount 40,000
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumSettlementGross(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumRefundedAgainstCaptures(DATE)).thenReturn(new BigDecimal("40000"));
        when(loadDailyTotalsPort.sumSettlementRefunded(DATE)).thenReturn(new BigDecimal("40000"));

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
        assertThat(r.captureDiscrepancy()).isEqualByComparingTo("0");
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("0");
        assertThat(r.discrepancy()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("캡처 축 불일치: 캡처했는데 정산이 누락되면 captureDiscrepancy>0")
    void captureAxisMismatch_missingSettlement() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumSettlementGross(DATE)).thenReturn(new BigDecimal("50000")); // 정산 1건 누락
        when(loadDailyTotalsPort.sumRefundedAgainstCaptures(DATE)).thenReturn(BigDecimal.ZERO);
        when(loadDailyTotalsPort.sumSettlementRefunded(DATE)).thenReturn(BigDecimal.ZERO);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isFalse();
        assertThat(r.captureDiscrepancy()).isEqualByComparingTo("10000");
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("환불 축 불일치: order 는 환불했는데 정산 미반영이면 refundDiscrepancy 로 감지")
    void refundAxisMismatch_unreflectedRefund() {
        // 2026-07-05 실사례: 역정산 컨슈머 결선 전 — order 환불 40,000 / settlement refunded 0
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumSettlementGross(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumRefundedAgainstCaptures(DATE)).thenReturn(new BigDecimal("40000"));
        when(loadDailyTotalsPort.sumSettlementRefunded(DATE)).thenReturn(BigDecimal.ZERO);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isFalse();
        assertThat(r.captureDiscrepancy()).isEqualByComparingTo("0");      // 캡처 축은 건강
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("40000");  // 미반영 환불 감지
        assertThat(r.discrepancy()).isEqualByComparingTo("40000");
    }

    @Test @DisplayName("null 값은 0 으로 취급 — 데이터 없는 날에도 NPE 없이 matched=true")
    void nullsAsZero() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementGross(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumRefundedAgainstCaptures(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementRefunded(DATE)).thenReturn(null);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
    }

    @Test @DisplayName("targetDate null 이면 예외")
    void nullTargetDate() {
        assertThatThrownBy(() -> service.reconcile(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
