package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementConfirmItemWriterTest {

    @Mock SaveSettlementPort saveSettlementPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    @Mock EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    @Mock PublishSettlementEventPort publishSettlementEventPort;
    @InjectMocks SettlementConfirmItemWriter writer;

    private Settlement confirmed(long id) {
        Settlement s = Settlement.createFromPayment(id, id + 10, new BigDecimal("10000"), LocalDate.now());
        s.assignId(id);
        s.confirm();
        return s;
    }

    @Test
    @DisplayName("청크의 각 정산 저장 + 원장 enqueue·ES 이벤트는 청크 id 목록으로 1회씩 발행")
    void writesChunkAndPublishesOnce() throws Exception {
        Settlement s1 = confirmed(1L);
        Settlement s2 = confirmed(2L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.of(91L));
        when(loadSellerIdPort.findSellerIdByPaymentId(2L)).thenReturn(Optional.of(92L));

        writer.write(new Chunk<>(List.of(s1, s2)));

        verify(saveSettlementPort).save(s1);
        verify(saveSettlementPort).save(s2);
        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(1L), eq(91L), any());
        verify(publishSettlementDomainEventPort).publishSettlementConfirmed(eq(2L), eq(92L), any());
        verify(enqueueLedgerTaskPort).enqueueCreate(List.of(1L, 2L));
        verify(publishSettlementEventPort).publishSettlementConfirmedEvent(List.of(1L, 2L));
    }

    @Test
    @DisplayName("판매자 미해석 정산은 SettlementConfirmed(loan) 발행 생략")
    void skipsLoanEventWhenSellerUnresolved() throws Exception {
        Settlement s1 = confirmed(1L);
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(1L)).thenReturn(Optional.empty());

        writer.write(new Chunk<>(List.of(s1)));

        verify(publishSettlementDomainEventPort, never()).publishSettlementConfirmed(anyLong(), anyLong(), any());
        verify(enqueueLedgerTaskPort).enqueueCreate(List.of(1L));
    }

    @Test
    @DisplayName("빈 청크면 원장 enqueue·ES 이벤트 발행 안 함")
    void emptyChunkNoSideEffects() throws Exception {
        writer.write(new Chunk<>(List.of()));

        verify(enqueueLedgerTaskPort, never()).enqueueCreate(any());
        verify(publishSettlementEventPort, never()).publishSettlementConfirmedEvent(any());
    }
}
