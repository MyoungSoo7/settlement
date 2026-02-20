package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.CreateDailySettlementsUseCase.CreateSettlementResult;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SettlementSearchIndexPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateDailySettlementsServiceTest {

    @Mock
    private LoadCapturedPaymentsPort loadCapturedPaymentsPort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private SettlementSearchIndexPort settlementSearchIndexPort;

    private CreateDailySettlementsService service;

    @BeforeEach
    void setUp() {
        service = new CreateDailySettlementsService(
                loadCapturedPaymentsPort,
                saveSettlementPort,
                settlementSearchIndexPort
        );
    }

    @Test
    @DisplayName("성공: 특정 날짜의 승인된 결제 내역으로 정산 생성")
    void testCreateDailySettlements_Success() {
        LocalDate targetDate = LocalDate.of(2024, 1, 15);
        CreateSettlementCommand command = new CreateSettlementCommand(targetDate);

        List<CapturedPaymentInfo> capturedPayments = Arrays.asList(
                new CapturedPaymentInfo(1L, 101L, new BigDecimal("10000"), LocalDateTime.now()),
                new CapturedPaymentInfo(2L, 102L, new BigDecimal("20000"), LocalDateTime.now()),
                new CapturedPaymentInfo(3L, 103L, new BigDecimal("30000"), LocalDateTime.now())
        );

        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(targetDate))
                .thenReturn(capturedPayments);

        when(saveSettlementPort.save(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement settlement = invocation.getArgument(0);
            settlement.setId(100L + settlement.getPaymentId());
            return settlement;
        });

        when(settlementSearchIndexPort.isSearchEnabled()).thenReturn(true);

        CreateSettlementResult result = service.createDailySettlements(command);

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.totalPayments()).isEqualTo(3);
        assertThat(result.targetDate()).isEqualTo(targetDate);

        verify(saveSettlementPort, times(3)).save(any(Settlement.class));
        verify(settlementSearchIndexPort, times(1)).bulkIndexSettlements(anyList());
    }

    @Test
    @DisplayName("검증: 결제 금액에서 수수료를 제외한 실 지급액 계산")
    void testCreateDailySettlements_CalculatesNetAmount() {
        LocalDate targetDate = LocalDate.of(2024, 1, 20);
        CreateSettlementCommand command = new CreateSettlementCommand(targetDate);

        CapturedPaymentInfo payment = new CapturedPaymentInfo(
                1L, 101L, new BigDecimal("10000"), LocalDateTime.now()
        );

        when(loadCapturedPaymentsPort.findCapturedPaymentsByDate(targetDate))
                .thenReturn(List.of(payment));

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);

        when(saveSettlementPort.save(any(Settlement.class))).thenAnswer(invocation -> {
            Settlement s = invocation.getArgument(0);
            s.setId(1L);
            return s;
        });

        when(settlementSearchIndexPort.isSearchEnabled()).thenReturn(true);

        service.createDailySettlements(command);

        verify(saveSettlementPort).save(settlementCaptor.capture());
        Settlement savedSettlement = settlementCaptor.getValue();

        assertThat(savedSettlement.getPaymentAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(savedSettlement.getCommission()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(savedSettlement.getNetAmount()).isEqualByComparingTo(new BigDecimal("9700.00"));
        assertThat(savedSettlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }
}
