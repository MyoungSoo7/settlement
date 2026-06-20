package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceTest {

    @Mock LoadCapturedPaymentsPort loadCapturedPaymentsPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock SettlementSearchIndexPort settlementSearchIndexPort;
    @InjectMocks CreateDailySettlementsService service;

    @Test @DisplayName("결제 없을 때 생성 건수 0")
    void noPayments() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(target)).thenReturn(List.of());

        var result = service.createDailySettlements(
                new CreateDailySettlementsUseCase.CreateSettlementCommand(target));

        assertThat(result.createdCount()).isZero();
        assertThat(result.totalPayments()).isZero();
        verify(saveSettlementPort, never()).save(any());
    }

    @Test @DisplayName("결제 3건 -> 정산 3건 생성 + 수수료 3% 계산")
    void createsThreeSettlements() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        var p1 = new LoadCapturedPaymentsPort.CapturedPaymentInfo(
                1L, 10L, new BigDecimal("10000"), LocalDateTime.now());
        var p2 = new LoadCapturedPaymentsPort.CapturedPaymentInfo(
                2L, 11L, new BigDecimal("20000"), LocalDateTime.now());
        var p3 = new LoadCapturedPaymentsPort.CapturedPaymentInfo(
                3L, 12L, new BigDecimal("50000"), LocalDateTime.now());
        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(target)).thenReturn(List.of(p1, p2, p3));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(100L + s.getPaymentId());
            return s;
        });
        when(settlementSearchIndexPort.isSearchEnabled()).thenReturn(false);

        var result = service.createDailySettlements(
                new CreateDailySettlementsUseCase.CreateSettlementCommand(target));

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.totalPayments()).isEqualTo(3);
        verify(saveSettlementPort, org.mockito.Mockito.times(3)).save(any());
    }

    @Test @DisplayName("검색 활성화 시 bulkIndex 호출")
    void searchEnabled_triggersBulkIndex() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        var p = new LoadCapturedPaymentsPort.CapturedPaymentInfo(
                1L, 10L, new BigDecimal("10000"), LocalDateTime.now());
        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(target)).thenReturn(List.of(p));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementSearchIndexPort.isSearchEnabled()).thenReturn(true);

        service.createDailySettlements(
                new CreateDailySettlementsUseCase.CreateSettlementCommand(target));

        verify(settlementSearchIndexPort).bulkIndexSettlements(anyList());
    }

    @Test @DisplayName("검색 인덱싱 실패해도 정산 생성 결과는 성공")
    void indexingFails_settlementStillCreated() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        var p = new LoadCapturedPaymentsPort.CapturedPaymentInfo(
                1L, 10L, new BigDecimal("10000"), LocalDateTime.now());
        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(target)).thenReturn(List.of(p));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementSearchIndexPort.isSearchEnabled()).thenReturn(true);
        doThrow(new RuntimeException("ES down"))
                .when(settlementSearchIndexPort).bulkIndexSettlements(anyList());

        var result = service.createDailySettlements(
                new CreateDailySettlementsUseCase.CreateSettlementCommand(target));

        assertThat(result.createdCount()).isEqualTo(1);
    }

    @Test @DisplayName("targetDate 가 null 이면 command 생성에서 예외")
    void nullTargetDate_throwsOnCommand() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new CreateDailySettlementsUseCase.CreateSettlementCommand(null));
    }
}
