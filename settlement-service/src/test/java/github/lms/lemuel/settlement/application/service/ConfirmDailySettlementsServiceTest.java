package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmDailySettlementsServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock PublishSettlementEventPort publishSettlementEventPort;
    @InjectMocks ConfirmDailySettlementsService service;

    private Settlement requestedSettlement(Long id) {
        Settlement s = Settlement.createFromPayment(id, id + 10, new BigDecimal("10000"), LocalDate.now());
        s.setId(id);
        return s;
    }

    @Test @DisplayName("REQUESTED 정산들을 confirm → DONE 전이 + 이벤트 발행")
    void confirmsPending() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        Settlement s1 = requestedSettlement(1L);
        Settlement s2 = requestedSettlement(2L);
        when(loadSettlementPort.findBySettlementDate(target)).thenReturn(List.of(s1, s2));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.confirmedCount()).isEqualTo(2);
        assertThat(result.totalSettlements()).isEqualTo(2);
        assertThat(s1.getStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(s2.getStatus()).isEqualTo(SettlementStatus.DONE);
        verify(publishSettlementEventPort).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("DONE 정산은 건너뜀, 이벤트도 발행 안 함")
    void skipsAlreadyDone() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        Settlement done = requestedSettlement(1L);
        done.startProcessing();
        done.complete();
        when(loadSettlementPort.findBySettlementDate(target)).thenReturn(List.of(done));

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.confirmedCount()).isZero();
        verify(saveSettlementPort, never()).save(any());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("대상 정산 없으면 이벤트 발행 안 함")
    void noSettlements() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        when(loadSettlementPort.findBySettlementDate(target)).thenReturn(List.of());

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.totalSettlements()).isZero();
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("targetDate null 이면 Command 생성 단계에서 예외")
    void nullTarget() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(null));
    }
}
