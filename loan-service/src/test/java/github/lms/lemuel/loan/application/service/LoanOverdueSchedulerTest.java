package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ManageLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanOverdueSchedulerTest {

    @Mock ManageLoanCollectionUseCase collectionUseCase;
    @Mock LoadLoanPort loadLoanPort;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), KST);

    // graceDays=0, writeOffDays=30
    private LoanOverdueScheduler scheduler() {
        return new LoanOverdueScheduler(collectionUseCase, loadLoanPort, clock, 0, 30);
    }

    private LoanAdvance loan(long id, LoanStatus status) {
        return LoanAdvance.reconstitute(id, 7L, new BigDecimal("800000"), new BigDecimal("800"),
                new BigDecimal("800800"), status, 7,
                LocalDateTime.of(2026, 7, 1, 9, 0), LocalDateTime.of(2026, 7, 8, 9, 0));
    }

    @Test
    void 연체승격은_만기경과_DISBURSED를_asOf_now로_스캔해_건별_markOverdue() {
        LocalDateTime expectedAsOf = LocalDateTime.now(clock); // grace 0 → now(KST)
        when(loadLoanPort.findOverdueCandidates(eq(expectedAsOf)))
                .thenReturn(List.of(loan(10L, LoanStatus.DISBURSED), loan(11L, LoanStatus.DISBURSED)));

        int done = scheduler().promoteOverdue();

        assertThat(done).isEqualTo(2);
        verify(collectionUseCase).markOverdue(10L);
        verify(collectionUseCase).markOverdue(11L);
    }

    @Test
    void 연체승격_건별실패는_격리하고_나머지는_계속한다() {
        when(loadLoanPort.findOverdueCandidates(eq(LocalDateTime.now(clock))))
                .thenReturn(List.of(loan(10L, LoanStatus.DISBURSED), loan(11L, LoanStatus.DISBURSED)));
        // 10L 은 스캔~처리 사이 상태가 바뀌어 전이 불가(격리 대상)
        when(collectionUseCase.markOverdue(10L)).thenThrow(new InvalidLoanStateException(
                LoanStatus.OVERDUE, LoanStatus.OVERDUE));

        int done = scheduler().promoteOverdue();

        assertThat(done).isEqualTo(1); // 11L 만 성공
        verify(collectionUseCase).markOverdue(11L);
    }

    @Test
    void 상각은_만기후_writeOffDays_경과_OVERDUE를_asOf_now빼기30으로_스캔해_건별_writeOff() {
        LocalDateTime expectedAsOf = LocalDateTime.now(clock).minusDays(30);
        when(loadLoanPort.findWriteOffCandidates(eq(expectedAsOf)))
                .thenReturn(List.of(loan(20L, LoanStatus.OVERDUE)));

        int done = scheduler().promoteWriteOff();

        assertThat(done).isEqualTo(1);
        verify(collectionUseCase).writeOff(20L);
        verify(collectionUseCase, never()).markOverdue(20L);
    }

    @Test
    void scan은_연체승격과_상각을_모두_수행한다() {
        when(loadLoanPort.findOverdueCandidates(eq(LocalDateTime.now(clock))))
                .thenReturn(List.of(loan(10L, LoanStatus.DISBURSED)));
        when(loadLoanPort.findWriteOffCandidates(eq(LocalDateTime.now(clock).minusDays(30))))
                .thenReturn(List.of(loan(20L, LoanStatus.OVERDUE)));

        scheduler().scan();

        verify(collectionUseCase).markOverdue(10L);
        verify(collectionUseCase).writeOff(20L);
    }
}
