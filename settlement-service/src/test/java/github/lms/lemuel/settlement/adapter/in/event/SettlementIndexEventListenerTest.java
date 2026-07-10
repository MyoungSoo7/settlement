package github.lms.lemuel.settlement.adapter.in.event;

import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import github.lms.lemuel.settlement.application.port.in.IndexSettlementUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementIndexEventListenerTest {

    @Mock IndexSettlementUseCase indexSettlementUseCase;

    SettlementIndexEventListener listener;

    private void setUp() {
        listener = new SettlementIndexEventListener(indexSettlementUseCase);
    }

    @Test
    @DisplayName("BATCH_CREATED — bulkIndexSettlements 로 벌크 인덱싱한다")
    void handlesBatchCreated_bulkIndexes() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(1L, 2L), SettlementIndexEvent.IndexEventType.BATCH_CREATED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).bulkIndexSettlements(List.of(1L, 2L));
        verify(indexSettlementUseCase, never()).indexSettlement(eq(1L));
    }

    @Test
    @DisplayName("BATCH_CONFIRMED — bulkIndexSettlements 로 벌크 인덱싱한다")
    void handlesBatchConfirmed_bulkIndexes() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(3L), SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).bulkIndexSettlements(List.of(3L));
    }

    @Test
    @DisplayName("REFUND_PROCESSED — bulkIndexSettlements 로 벌크 인덱싱한다")
    void handlesRefundProcessed_bulkIndexes() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(4L), SettlementIndexEvent.IndexEventType.REFUND_PROCESSED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).bulkIndexSettlements(List.of(4L));
    }

    @Test
    @DisplayName("SINGLE_UPDATED — 각 id 를 개별 인덱싱한다")
    void handlesSingleUpdated_indexesEachId() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(10L, 20L), SettlementIndexEvent.IndexEventType.SINGLE_UPDATED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).indexSettlement(10L);
        verify(indexSettlementUseCase).indexSettlement(20L);
    }

    @Test
    @DisplayName("APPROVED — 각 id 를 개별 인덱싱한다")
    void handlesApproved_indexesEachId() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(11L), SettlementIndexEvent.IndexEventType.APPROVED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).indexSettlement(11L);
    }

    @Test
    @DisplayName("REJECTED — 각 id 를 개별 인덱싱한다")
    void handlesRejected_indexesEachId() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(12L), SettlementIndexEvent.IndexEventType.REJECTED);

        listener.handleSettlementIndexEvent(event);

        verify(indexSettlementUseCase).indexSettlement(12L);
    }

    @Test
    @DisplayName("UseCase 가 예외를 던져도 리스너 밖으로 전파하지 않는다 (재시도 큐는 UseCase 내부 책임)")
    void handlesUseCaseException_doesNotPropagate() {
        setUp();
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(1L), SettlementIndexEvent.IndexEventType.BATCH_CREATED);
        doThrow(new RuntimeException("es down")).when(indexSettlementUseCase).bulkIndexSettlements(List.of(1L));

        assertThatNoException().isThrownBy(() -> listener.handleSettlementIndexEvent(event));
    }
}
