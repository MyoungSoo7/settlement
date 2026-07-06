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

    @Test @DisplayName("일치: 결제 gross = net+commission, 환불 = 조정 → matched=true")
    void matched() {
        // 결제 축: 캡처 gross 100,000 = net 96,500 + commission 3,500 (NORMAL 3.5%)
        // 환불 축: 환불 10,000 = 환불 조정 10,000
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("100000"));
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(new BigDecimal("10000"));
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(new BigDecimal("96500"));
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(new BigDecimal("3500"));
        when(loadDailyTotalsPort.sumRefundAdjustments(DATE)).thenReturn(new BigDecimal("10000"));

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
        assertThat(r.paymentDiscrepancy()).isEqualByComparingTo("0");
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("0");
        assertThat(r.discrepancy()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("결제 축 불일치: 정산에서 100원 새면 paymentDiscrepancy=100")
    void paymentAxisMismatch() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("100000"));
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(BigDecimal.ZERO);
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(new BigDecimal("96400")); // 100원 모자람
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(new BigDecimal("3500"));
        when(loadDailyTotalsPort.sumRefundAdjustments(DATE)).thenReturn(BigDecimal.ZERO);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isFalse();
        assertThat(r.paymentDiscrepancy()).isEqualByComparingTo("100");
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("환불 축 불일치: 환불은 있는데 역정산 조정이 없으면 refundDiscrepancy 로 감지")
    void refundAxisMismatch_missingReverseSettlement() {
        // 2026-07-05 실사례: 환불 40,000 완료됐지만 settlement_adjustments 0건
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("60000"));
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(new BigDecimal("40000"));
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(new BigDecimal("57900"));
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(new BigDecimal("2100"));
        when(loadDailyTotalsPort.sumRefundAdjustments(DATE)).thenReturn(BigDecimal.ZERO);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isFalse();
        assertThat(r.paymentDiscrepancy()).isEqualByComparingTo("0");     // 결제 축은 건강
        assertThat(r.refundDiscrepancy()).isEqualByComparingTo("40000"); // 역정산 누락 감지
        assertThat(r.discrepancy()).isEqualByComparingTo("40000");
    }

    @Test @DisplayName("null 값은 0 으로 취급 — 데이터 없는 날에도 NPE 없이 matched=true")
    void nullsAsZero() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumRefundAdjustments(DATE)).thenReturn(null);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
    }

    @Test @DisplayName("targetDate null 이면 예외")
    void nullTargetDate() {
        assertThatThrownBy(() -> service.reconcile(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
