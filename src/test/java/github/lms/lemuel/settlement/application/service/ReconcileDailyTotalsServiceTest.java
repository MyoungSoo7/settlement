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

    @Test @DisplayName("일치: 결제 - 환불 = net + commission → matched=true")
    void matched() {
        // 결제 100,000 - 환불 10,000 = 90,000 = net 87,300 + commission 2,700 (3%)
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("100000"));
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(new BigDecimal("10000"));
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(new BigDecimal("87300"));
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(new BigDecimal("2700"));

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
        assertThat(r.discrepancy()).isEqualByComparingTo("0");
    }

    @Test @DisplayName("불일치: 원장에서 100원 샌 경우 discrepancy>0")
    void mismatched() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(new BigDecimal("100000"));
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(BigDecimal.ZERO);
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(new BigDecimal("96900")); // 100원 모자람
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(new BigDecimal("3000"));

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isFalse();
        assertThat(r.discrepancy()).isEqualByComparingTo("100");
    }

    @Test @DisplayName("null 값은 0 으로 취급 — 데이터 없는 날에도 NPE 없이 matched=true")
    void nullsAsZero() {
        when(loadDailyTotalsPort.sumCapturedPayments(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumCompletedRefunds(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementNet(DATE)).thenReturn(null);
        when(loadDailyTotalsPort.sumSettlementCommission(DATE)).thenReturn(null);

        ReconciliationReport r = service.reconcile(DATE);

        assertThat(r.matched()).isTrue();
    }

    @Test @DisplayName("targetDate null 이면 예외")
    void nullTargetDate() {
        assertThatThrownBy(() -> service.reconcile(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
