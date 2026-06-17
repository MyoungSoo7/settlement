package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmDailySettlementsServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock PublishSettlementEventPort publishSettlementEventPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
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
        when(loadSettlementPort.findConfirmableForUpdate(target)).thenReturn(List.of(s1, s2));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.confirmedCount()).isEqualTo(2);
        assertThat(result.totalSettlements()).isEqualTo(2);
        assertThat(s1.getStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(s2.getStatus()).isEqualTo(SettlementStatus.DONE);
        verify(enqueueLedgerTaskPort).enqueueCreate(anyList());
        verify(publishSettlementEventPort).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("판매자 해석되면 각 확정 정산마다 SettlementConfirmed(loan) 발행")
    void publishesConfirmedPerSettlement() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        Settlement s1 = requestedSettlement(1L);
        Settlement s2 = requestedSettlement(2L);
        when(loadSettlementPort.findConfirmableForUpdate(target)).thenReturn(List.of(s1, s2));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(loadSellerIdPort.findSellerIdByPaymentId(2L)).thenReturn(Optional.of(92L));

        service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(1L), eq(91L), any());
        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(2L), eq(92L), any());
    }

    @Test @DisplayName("방어적 isPending: 락 조회가 DONE 행을 반환해도 건너뜀, 이벤트도 발행 안 함")
    void skipsAlreadyDone() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        Settlement done = requestedSettlement(1L);
        done.startProcessing();
        done.complete();
        when(loadSettlementPort.findConfirmableForUpdate(target)).thenReturn(List.of(done));

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.confirmedCount()).isZero();
        verify(saveSettlementPort, never()).save(any());
        verify(enqueueLedgerTaskPort, never()).enqueueCreate(anyList());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("대상 정산 없으면 이벤트 발행 안 함")
    void noSettlements() {
        LocalDate target = LocalDate.of(2026, 4, 22);
        when(loadSettlementPort.findConfirmableForUpdate(target)).thenReturn(List.of());

        var result = service.confirmDailySettlements(
                new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(target));

        assertThat(result.totalSettlements()).isZero();
        verify(enqueueLedgerTaskPort, never()).enqueueCreate(anyList());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(anyList());
    }

    @Test @DisplayName("targetDate null 이면 Command 생성 단계에서 예외")
    void nullTarget() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ConfirmDailySettlementsUseCase.ConfirmSettlementCommand(null));
    }
}
