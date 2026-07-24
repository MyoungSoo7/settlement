package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RepayCorporateLoanUseCase.RepayCorporateLoanCommand;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepayCorporateLoanServiceTest {

    @Mock LoadCorporateLoanPort loadCorporateLoanPort;
    @Mock SaveCorporateLoanPort saveCorporateLoanPort;
    @Mock AppendLedgerPort appendLedgerPort;
    @Mock LoanMetricsPort loanMetricsPort;

    private RepayCorporateLoanService service() {
        return new RepayCorporateLoanService(loadCorporateLoanPort, saveCorporateLoanPort,
                appendLedgerPort, loanMetricsPort);
    }

    /** DISBURSED 상태(미상환잔액=outstanding)의 기업대출. */
    private CorporateLoan disbursed(BigDecimal outstanding) {
        return CorporateLoan.reconstitute(5001L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6000"), outstanding,
                30, 82, "A", CorporateLoanStatus.DISBURSED, null);
    }

    @Test
    void 부분상환은_잔액을_차감하고_상환전표1건_기록하며_DISBURSED유지() {
        when(loadCorporateLoanPort.findByIdForUpdate(5001L))
                .thenReturn(Optional.of(disbursed(new BigDecimal("1006000"))));
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorporateLoan result = service().repay(new RepayCorporateLoanCommand(5001L, new BigDecimal("600000")));

        assertThat(result.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
        assertThat(result.getOutstanding()).isEqualByComparingTo("406000");

        ArgumentCaptor<LoanLedgerEntry> captor = ArgumentCaptor.forClass(LoanLedgerEntry.class);
        verify(appendLedgerPort).append(captor.capture());
        assertThat(captor.getValue().getRefType()).isEqualTo("CORP_REPAYMENT");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("600000");
        // 도메인이 Money(scale 2, HALF_UP)로 정규화하므로 차감액은 600000.00 (Mockito equals 는 scale 구분).
        verify(loanMetricsPort).corporateRepaid(new BigDecimal("600000.00"));
    }

    @Test
    void 잔액초과_상환은_잔액까지만_차감하고_REPAID로_전이() {
        when(loadCorporateLoanPort.findByIdForUpdate(5001L))
                .thenReturn(Optional.of(disbursed(new BigDecimal("400660"))));
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorporateLoan result = service().repay(new RepayCorporateLoanCommand(5001L, new BigDecimal("999999")));

        assertThat(result.getStatus()).isEqualTo(CorporateLoanStatus.REPAID);
        assertThat(result.getOutstanding()).isEqualByComparingTo("0");

        ArgumentCaptor<LoanLedgerEntry> captor = ArgumentCaptor.forClass(LoanLedgerEntry.class);
        verify(appendLedgerPort).append(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("400660"); // clamp
    }

    @Test
    void 대출이_없으면_NotFound이고_저장_전표없음() {
        when(loadCorporateLoanPort.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().repay(new RepayCorporateLoanCommand(999L, BigDecimal.TEN)))
                .isInstanceOf(CorporateLoanNotFoundException.class);

        verify(saveCorporateLoanPort, never()).save(any());
        verify(appendLedgerPort, never()).append(any());
        verify(loanMetricsPort, never()).corporateRepaid(any());
    }

    @Test
    void 상환액이_0이하면_도메인예외이고_저장_전표없음() {
        when(loadCorporateLoanPort.findByIdForUpdate(5001L))
                .thenReturn(Optional.of(disbursed(new BigDecimal("1006000"))));

        assertThatThrownBy(() -> service().repay(new RepayCorporateLoanCommand(5001L, BigDecimal.ZERO)))
                .isInstanceOf(LoanInvariantViolationException.class);

        verify(saveCorporateLoanPort, never()).save(any());
        verify(appendLedgerPort, never()).append(any());
    }
}
