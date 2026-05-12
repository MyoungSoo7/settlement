package github.lms.lemuel.ledger.adapter.in.event;

import github.lms.lemuel.ledger.adapter.in.event.dto.LedgerReverseEntryEvent;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * LedgerReverseEntryEventListener — LedgerReverseEntryEvent 를 ReverseEntryUseCase 호출로 변환,
 * 예외는 외부로 전파하지 않고 흡수 (환불 자체 트랜잭션은 이미 commit 됨).
 */
class LedgerReverseEntryEventListenerTest {

    private ReverseEntryUseCase useCase;
    private LedgerReverseEntryEventListener listener;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        useCase = mock(ReverseEntryUseCase.class);
        listener = new LedgerReverseEntryEventListener(useCase);
    }

    @Test
    void 정상_이벤트는_UseCase_정확한_인자로_호출() {
        LedgerReverseEntryEvent event = new LedgerReverseEntryEvent(
                100L, 99L, new BigDecimal("5000"), TODAY);

        listener.handle(event);

        verify(useCase).reverseForRefund(eq(100L), eq(99L), eq(new BigDecimal("5000")), eq(TODAY));
    }

    @Test
    void null_이벤트는_안전하게_skip() {
        listener.handle(null);

        verifyNoInteractions(useCase);
    }

    @Test
    void UseCase_가_예외를_던져도_외부로_전파되지_않음() {
        doThrow(new RuntimeException("ledger db down"))
                .when(useCase).reverseForRefund(any(), any(), any(), any());
        LedgerReverseEntryEvent event = new LedgerReverseEntryEvent(
                100L, 99L, new BigDecimal("5000"), TODAY);

        // 예외 흡수 — listener 가 throw 하면 spring 이 다른 listener 들에 영향 줄 수 있음
        assertThatCode(() -> listener.handle(event)).doesNotThrowAnyException();

        verify(useCase).reverseForRefund(any(), any(), any(), any());
    }
}
