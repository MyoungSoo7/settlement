package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.out.AppendLedgerPort;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisburseLoanServiceTest {

    @Mock LoadLoanPort loadLoanPort;
    @Mock SaveLoanPort saveLoanPort;
    @Mock LoadSettlementViewPort loadSettlementViewPort;
    @Mock LoadSellerReputationPort loadSellerReputationPort;
    @Mock PublishLoanEventPort publishLoanEventPort;
    @Mock AppendLedgerPort appendLedgerPort;

    private final CreditPolicy creditPolicy = new CreditPolicy(new BigDecimal("0.80"), new BigDecimal("0.0002"),
            Map.of("A", BigDecimal.ONE, "B", BigDecimal.ONE, "C", new BigDecimal("0.85"),
                    "D", new BigDecimal("0.70"), "E", BigDecimal.ZERO));

    private DisburseLoanService service() {
        // 평판 미상 기본(무변동) — 개별 테스트에서 필요 시 재stub
        lenient().when(loadSellerReputationPort.findGrade(7L)).thenReturn(Optional.empty());
        return new DisburseLoanService(loadLoanPort, saveLoanPort, loadSettlementViewPort,
                loadSellerReputationPort, creditPolicy, publishLoanEventPort, appendLedgerPort);
    }

    private LoanAdvance requestedLoan() {
        // id=1, seller=7, principal=800,000, fee=800
        return LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"), new BigDecimal("800"),
                BigDecimal.ZERO, LoanStatus.REQUESTED);
    }

    @Test
    void 담보충분하면_실행하고_선지급이벤트를_발행한다() {
        when(loadLoanPort.load(1L)).thenReturn(requestedLoan());
        when(loadSettlementViewPort.sumUnpaidBySellerForUpdate(7L)).thenReturn(new BigDecimal("1000000"));
        when(saveLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LoanAdvance result = service().disburse(1L);

        assertThat(result.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(result.getOutstanding()).isEqualByComparingTo("800800"); // 원금+수수료
        verify(publishLoanEventPort).publishDisbursementRequested(any());
        // 복식부기 전표 2건: 선지급 + 수수료 인식
        verify(appendLedgerPort, org.mockito.Mockito.times(2)).append(any());
    }

    @Test
    void 실행시점_담보부족이면_거절하고_발행하지_않는다() {
        when(loadLoanPort.load(1L)).thenReturn(requestedLoan());
        // 담보 50만 → 한도 40만 < 신청 80만 → 부족
        when(loadSettlementViewPort.sumUnpaidBySellerForUpdate(7L)).thenReturn(new BigDecimal("500000"));

        assertThatThrownBy(() -> service().disburse(1L))
                .isInstanceOf(IllegalStateException.class);

        verify(publishLoanEventPort, never()).publishDisbursementRequested(any());
        // 거절 상태로 저장됐는지
        org.mockito.ArgumentCaptor<LoanAdvance> captor = org.mockito.ArgumentCaptor.forClass(LoanAdvance.class);
        verify(saveLoanPort).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(LoanStatus.REJECTED);
    }
}
