package github.lms.lemuel.ledger.adapter.in.event;

import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * LedgerCreationEventListener — SettlementIndexEvent 수신 시 BATCH_CONFIRMED 만 통과시키고
 * 나머지 타입은 무시하는 필터링 wiring 검증.
 */
class LedgerCreationEventListenerTest {

    private CreateLedgerEntryUseCase useCase;
    private LedgerCreationEventListener listener;

    @BeforeEach
    void setUp() {
        useCase = mock(CreateLedgerEntryUseCase.class);
        listener = new LedgerCreationEventListener(useCase);
    }

    @Test
    void BATCH_CONFIRMED_이벤트는_UseCase_호출() {
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(1L, 2L, 3L),
                SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED);

        listener.handleSettlementConfirmed(event);

        verify(useCase).createFromSettlements(eq(List.of(1L, 2L, 3L)));
    }

    @Test
    void BATCH_CREATED_는_무시() {
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(1L),
                SettlementIndexEvent.IndexEventType.BATCH_CREATED);

        listener.handleSettlementConfirmed(event);

        verifyNoInteractions(useCase);
    }

    @Test
    void REFUND_PROCESSED_는_무시() {
        SettlementIndexEvent event = new SettlementIndexEvent(
                List.of(1L),
                SettlementIndexEvent.IndexEventType.REFUND_PROCESSED);

        listener.handleSettlementConfirmed(event);

        verifyNoInteractions(useCase);
    }

    @Test
    void SINGLE_UPDATED_APPROVED_REJECTED_도_모두_무시() {
        for (SettlementIndexEvent.IndexEventType t : new SettlementIndexEvent.IndexEventType[]{
                SettlementIndexEvent.IndexEventType.SINGLE_UPDATED,
                SettlementIndexEvent.IndexEventType.APPROVED,
                SettlementIndexEvent.IndexEventType.REJECTED}) {
            listener.handleSettlementConfirmed(new SettlementIndexEvent(List.of(1L), t));
        }

        verify(useCase, never()).createFromSettlements(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void null_이벤트는_안전하게_skip() {
        listener.handleSettlementConfirmed(null);

        verifyNoInteractions(useCase);
    }
}
