package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishCorporateLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisburseCorporateLoanServiceTest {

    @Mock LoadCorporateLoanPort loadCorporateLoanPort;
    @Mock SaveCorporateLoanPort saveCorporateLoanPort;
    @Mock AppendLedgerPort appendLedgerPort;
    @Mock PublishCorporateLoanEventPort publishCorporateLoanEventPort;
    @Mock LoanMetricsPort loanMetricsPort;

    private DisburseCorporateLoanService service() {
        return new DisburseCorporateLoanService(loadCorporateLoanPort, saveCorporateLoanPort,
                appendLedgerPort, publishCorporateLoanEventPort, loanMetricsPort);
    }

    private CorporateLoan requested(BigDecimal principal, BigDecimal fee) {
        return CorporateLoan.reconstitute(5001L, "005930", "삼성전자", principal, fee,
                BigDecimal.ZERO, 30, 82, "A", CorporateLoanStatus.REQUESTED, null);
    }

    @Test
    void 실행하면_전표2건과_실행이벤트를_발행하고_DISBURSED() {
        when(loadCorporateLoanPort.findByIdForUpdate(5001L)).thenReturn(
                Optional.of(requested(new BigDecimal("1000000"), new BigDecimal("6000"))));
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorporateLoan result = service().disburse(5001L);

        assertThat(result.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
        assertThat(result.getOutstanding()).isEqualByComparingTo("1006000"); // 원금+수수료

        // 복식부기 전표 2건: 선지급(CORP_DISBURSE) + 수수료 인식(CORP_FEE)
        ArgumentCaptor<LoanLedgerEntry> captor = ArgumentCaptor.forClass(LoanLedgerEntry.class);
        verify(appendLedgerPort, times(2)).append(captor.capture());
        assertThat(captor.getAllValues()).extracting(LoanLedgerEntry::getRefType)
                .containsExactly("CORP_DISBURSE", "CORP_FEE");

        verify(publishCorporateLoanEventPort).publishDisbursed(any());
        verify(loanMetricsPort).corporateDisbursed();
    }

    @Test
    void 수수료가_0이면_선지급전표_1건만() {
        when(loadCorporateLoanPort.findByIdForUpdate(5001L)).thenReturn(
                Optional.of(requested(new BigDecimal("1000000"), BigDecimal.ZERO)));
        when(saveCorporateLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().disburse(5001L);

        verify(appendLedgerPort, times(1)).append(any());
        verify(publishCorporateLoanEventPort).publishDisbursed(any());
    }

    @Test
    void 대출이_없으면_NotFound이고_발행하지_않는다() {
        when(loadCorporateLoanPort.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().disburse(999L))
                .isInstanceOf(CorporateLoanNotFoundException.class);

        verify(saveCorporateLoanPort, never()).save(any());
        verify(appendLedgerPort, never()).append(any());
        verify(publishCorporateLoanEventPort, never()).publishDisbursed(any());
        verify(loanMetricsPort, never()).corporateDisbursed();
    }
}
