package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase.ApplyRepaymentCommand;
import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoanMetricsPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.RecordRepaymentPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyRepaymentServiceTest {

    @Mock LoadLoanPort loadLoanPort;
    @Mock SaveLoanPort saveLoanPort;
    @Mock RecordRepaymentPort recordRepaymentPort;
    @Mock SaveSettlementViewPort saveSettlementViewPort;
    @Mock PublishLoanEventPort publishLoanEventPort;
    @Mock AppendLedgerPort appendLedgerPort;
    @Mock LoanMetricsPort loanMetricsPort;

    private ApplyRepaymentService service() {
        return new ApplyRepaymentService(loadLoanPort, saveLoanPort, recordRepaymentPort,
                saveSettlementViewPort, publishLoanEventPort, appendLedgerPort, loanMetricsPort);
    }

    private LoanAdvance disbursed(long id, BigDecimal outstanding) {
        return LoanAdvance.reconstitute(id, 7L, outstanding, BigDecimal.ZERO, outstanding, LoanStatus.DISBURSED);
    }

    @Test
    void 다건대출은_FIFO로_차감되고_총차감액을_발행한다() {
        LoanAdvance oldest = disbursed(1L, new BigDecimal("300000"));
        LoanAdvance newer = disbursed(2L, new BigDecimal("600000"));
        when(recordRepaymentPort.existsForSettlement(100L)).thenReturn(false);
        when(loadLoanPort.findDisbursedBySellerForUpdate(7L)).thenReturn(List.of(oldest, newer));

        // 정산금 80만 → oldest 30만 전액, newer 50만 부분, 총 80만
        service().apply(new ApplyRepaymentCommand(100L, 7L, new BigDecimal("800000")));

        assertThat(oldest.getOutstanding()).isEqualByComparingTo("0");
        assertThat(oldest.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(newer.getOutstanding()).isEqualByComparingTo("100000"); // 600000 - 500000
        // 차감액은 Money(scale 2) 표준형 — 프로덕션 DB(NUMERIC 19,2) 로드값과 동일 스케일.
        verify(recordRepaymentPort).record(eq(100L), eq(7L), eq(new BigDecimal("800000.00")));
        verify(publishLoanEventPort).publishRepaymentApplied(100L, 7L, new BigDecimal("800000.00"));
        verify(saveSettlementViewPort).markConfirmed(100L);
        verify(loanMetricsPort).repaymentApplied(new BigDecimal("800000.00"));
    }

    @Test
    void 대출없는_셀러는_차감0으로_기록하고_발행한다() {
        when(recordRepaymentPort.existsForSettlement(101L)).thenReturn(false);
        when(loadLoanPort.findDisbursedBySellerForUpdate(7L)).thenReturn(List.of());

        service().apply(new ApplyRepaymentCommand(101L, 7L, new BigDecimal("500000")));

        verify(recordRepaymentPort).record(101L, 7L, BigDecimal.ZERO);
        verify(publishLoanEventPort).publishRepaymentApplied(101L, 7L, BigDecimal.ZERO);
        verify(saveLoanPort, never()).save(any());
        // 차감 0이어도 상환 처리 발생은 카운트한다(멱등 통지 경로).
        verify(loanMetricsPort).repaymentApplied(BigDecimal.ZERO);
    }

    @Test
    void 정산금이_미상환보다_크면_미상환만큼만_차감() {
        LoanAdvance loan = disbursed(1L, new BigDecimal("200000"));
        when(recordRepaymentPort.existsForSettlement(102L)).thenReturn(false);
        when(loadLoanPort.findDisbursedBySellerForUpdate(7L)).thenReturn(List.of(loan));

        service().apply(new ApplyRepaymentCommand(102L, 7L, new BigDecimal("500000")));

        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        verify(recordRepaymentPort).record(102L, 7L, new BigDecimal("200000.00")); // 잔액만큼만
        verify(publishLoanEventPort).publishRepaymentApplied(102L, 7L, new BigDecimal("200000.00"));
    }

    @Test
    void 첫_대출이_정산금을_모두_소진하면_나머지_대출은_건너뛴다() {
        LoanAdvance first = disbursed(1L, new BigDecimal("300000"));
        LoanAdvance second = disbursed(2L, new BigDecimal("500000"));
        when(recordRepaymentPort.existsForSettlement(103L)).thenReturn(false);
        when(loadLoanPort.findDisbursedBySellerForUpdate(7L)).thenReturn(List.of(first, second));

        // 정산금 30만 = first 잔액과 동일 → first 전액 차감 후 remaining 0 → second 는 break 로 스킵
        service().apply(new ApplyRepaymentCommand(103L, 7L, new BigDecimal("300000")));

        assertThat(first.getOutstanding()).isEqualByComparingTo("0");
        assertThat(first.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(second.getOutstanding()).isEqualByComparingTo("500000"); // 손대지 않음
        assertThat(second.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        verify(saveLoanPort).save(first);
        verify(saveLoanPort, never()).save(second);
        verify(recordRepaymentPort).record(103L, 7L, new BigDecimal("300000.00"));
    }

    @Test
    void 이미_처리된_정산건은_멱등_스킵() {
        when(recordRepaymentPort.existsForSettlement(100L)).thenReturn(true);

        service().apply(new ApplyRepaymentCommand(100L, 7L, new BigDecimal("800000")));

        verify(loadLoanPort, never()).findDisbursedBySellerForUpdate(any());
        verify(saveLoanPort, never()).save(any());
        verify(publishLoanEventPort, never()).publishRepaymentApplied(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verify(loanMetricsPort, never()).repaymentApplied(any());
    }
}
