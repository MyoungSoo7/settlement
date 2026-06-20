package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.QuerySettlementPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementQueryServiceTest {

    @Mock QuerySettlementPort querySettlementPort;
    @InjectMocks SettlementQueryService service;

    @Test @DisplayName("getDailySummary: 일별 정산 요약 조회")
    void getDailySummary() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 1, 31);
        when(querySettlementPort.findDailySummary(start, end)).thenReturn(List.of());
        assertThat(service.getDailySummary(start, end)).isEmpty();
        verify(querySettlementPort).findDailySummary(start, end);
    }

    @Test @DisplayName("getMonthlySummary: 월별 정산 요약 조회")
    void getMonthlySummary() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 12, 31);
        when(querySettlementPort.findMonthlySummary(start, end)).thenReturn(List.of());
        assertThat(service.getMonthlySummary(start, end)).isEmpty();
    }

    @Test @DisplayName("getPaymentRefundAggregation: 결제/환불 집계")
    void getPaymentRefundAggregation() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 1, 31);
        when(querySettlementPort.getPaymentRefundAggregation(start, end)).thenReturn(null);
        assertThat(service.getPaymentRefundAggregation(start, end)).isNull();
    }

    @Test @DisplayName("getReconciliationMismatches: 대사 불일치 조회")
    void getReconciliationMismatches() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 1, 31);
        when(querySettlementPort.findReconciliationMismatches(start, end)).thenReturn(List.of());
        assertThat(service.getReconciliationMismatches(start, end)).isEmpty();
    }
}
