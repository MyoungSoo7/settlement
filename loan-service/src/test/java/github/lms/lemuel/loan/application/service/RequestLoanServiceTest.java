package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.LoadSettlementViewPort;
import github.lms.lemuel.loan.application.port.out.SaveLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestLoanServiceTest {

    @Mock LoadSettlementViewPort loadSettlementViewPort;
    @Mock LoadSellerReputationPort loadSellerReputationPort;
    @Mock SaveLoanPort saveLoanPort;

    private final CreditPolicy creditPolicy = new CreditPolicy(new BigDecimal("0.80"), new BigDecimal("0.0002"),
            Map.of("A", BigDecimal.ONE, "B", BigDecimal.ONE, "C", new BigDecimal("0.85"),
                    "D", new BigDecimal("0.70"), "E", BigDecimal.ZERO));

    private RequestLoanService service() {
        return new RequestLoanService(loadSettlementViewPort, loadSellerReputationPort, saveLoanPort, creditPolicy);
    }

    @Test
    void 한도이내_신청은_수수료를_산정해_REQUESTED로_저장한다() {
        when(loadSettlementViewPort.sumUnpaidBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadSellerReputationPort.findGrade(7L)).thenReturn(Optional.empty());  // 평판 미상 → 무변동
        when(saveLoanPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 한도 = 100만 × 0.8 = 80만. 신청 80만(한도 이내), 5일 → 수수료 800
        service().request(new RequestLoanCommand(7L, new BigDecimal("800000"), 5));

        ArgumentCaptor<LoanAdvance> captor = ArgumentCaptor.forClass(LoanAdvance.class);
        verify(saveLoanPort).save(captor.capture());
        LoanAdvance saved = captor.getValue();
        assertThat(saved.getSellerId()).isEqualTo(7L);
        assertThat(saved.getPrincipal()).isEqualByComparingTo("800000");
        assertThat(saved.getFee()).isEqualByComparingTo("800");
        assertThat(saved.getStatus()).isEqualTo(LoanStatus.REQUESTED);
    }

    @Test
    void 한도초과_신청은_예외이고_저장하지_않는다() {
        when(loadSettlementViewPort.sumUnpaidBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadSellerReputationPort.findGrade(7L)).thenReturn(Optional.empty());

        // 한도 80만인데 90만 신청 → 초과
        assertThatThrownBy(() ->
                service().request(new RequestLoanCommand(7L, new BigDecimal("900000"), 5)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(saveLoanPort, never()).save(any());
    }

    @Test
    void 평판_D등급_셀러는_한도가_깎여_같은_신청이_거절된다() {
        when(loadSettlementViewPort.sumUnpaidBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadSellerReputationPort.findGrade(7L)).thenReturn(Optional.of("D"));  // haircut 0.70

        // 평판 미상이면 통과할 80만이지만, D등급 한도 = 80만 × 0.70 = 56만 → 초과
        assertThatThrownBy(() ->
                service().request(new RequestLoanCommand(7L, new BigDecimal("800000"), 5)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(saveLoanPort, never()).save(any());
    }
}
