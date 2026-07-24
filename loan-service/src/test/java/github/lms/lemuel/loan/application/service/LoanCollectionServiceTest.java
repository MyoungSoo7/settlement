package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.LoanStatus;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanCollectionServiceTest {

    @Mock LoadLoanPort loadLoanPort;
    @Mock SaveLoanPort saveLoanPort;
    @Mock AppendLedgerPort appendLedgerPort;
    @Mock LoanMetricsPort loanMetricsPort;

    private LoanCollectionService service() {
        return new LoanCollectionService(loadLoanPort, saveLoanPort, appendLedgerPort, loanMetricsPort);
    }

    private LoanAdvance withStatus(LoanStatus status, BigDecimal outstanding) {
        return LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), outstanding, status);
    }

    @Test
    void 연체진입은_OVERDUE로_전이하고_전표없이_메트릭만() {
        when(loadLoanPort.load(1L)).thenReturn(withStatus(LoanStatus.DISBURSED, new BigDecimal("800800")));
        when(saveLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoanAdvance result = service().markOverdue(1L);

        assertThat(result.getStatus()).isEqualTo(LoanStatus.OVERDUE);
        verify(loanMetricsPort).advanceOverdue();
        verify(appendLedgerPort, never()).append(any());   // 연체는 상태전이만 — 전표 없음
    }

    @Test
    void 상각은_WRITTEN_OFF로_전이하고_대손전표1건_기록() {
        when(loadLoanPort.load(1L)).thenReturn(withStatus(LoanStatus.OVERDUE, new BigDecimal("800800")));
        when(saveLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoanAdvance result = service().writeOff(1L);

        assertThat(result.getStatus()).isEqualTo(LoanStatus.WRITTEN_OFF);

        ArgumentCaptor<LoanLedgerEntry> captor = ArgumentCaptor.forClass(LoanLedgerEntry.class);
        verify(appendLedgerPort).append(captor.capture());
        assertThat(captor.getValue().getRefType()).isEqualTo("BAD_DEBT");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("800800"); // 상각 손실=미상환잔액
        verify(loanMetricsPort).advanceWrittenOff(new BigDecimal("800800"));
    }

    @Test
    void DISBURSED에서_바로_상각하면_상태예외이고_저장_전표없음() {
        when(loadLoanPort.load(1L)).thenReturn(withStatus(LoanStatus.DISBURSED, new BigDecimal("800800")));

        assertThatThrownBy(() -> service().writeOff(1L))
                .isInstanceOf(InvalidLoanStateException.class);

        verify(saveLoanPort, never()).save(any());
        verify(appendLedgerPort, never()).append(any());
    }
}
