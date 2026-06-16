package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.RecordLoanDeductionPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyLoanDeductionServiceTest {

    @Mock RecordLoanDeductionPort recordLoanDeductionPort;
    @Mock LoadSettlementPort loadSettlementPort;

    private ApplyLoanDeductionService service() {
        return new ApplyLoanDeductionService(recordLoanDeductionPort, loadSettlementPort);
    }

    @Test
    void 차감을_정산건별로_기록한다() {
        service().apply(100L, 7L, new BigDecimal("800800"));
        verify(recordLoanDeductionPort).record(100L, 7L, new BigDecimal("800800"));
    }

    @Test
    void 순지급액은_netAmount에서_차감액을_뺀다() {
        Settlement s = mock(Settlement.class);
        when(s.getNetAmount()).thenReturn(new BigDecimal("1000000"));
        when(loadSettlementPort.findById(100L)).thenReturn(Optional.of(s));
        when(recordLoanDeductionPort.findDeduction(100L)).thenReturn(Optional.of(new BigDecimal("800800")));

        assertThat(service().netPayoutFor(100L)).isEqualByComparingTo("199200"); // 1,000,000 - 800,800
    }

    @Test
    void 차감기록_없으면_전액() {
        Settlement s = mock(Settlement.class);
        when(s.getNetAmount()).thenReturn(new BigDecimal("500000"));
        when(loadSettlementPort.findById(200L)).thenReturn(Optional.of(s));
        when(recordLoanDeductionPort.findDeduction(200L)).thenReturn(Optional.empty());

        assertThat(service().netPayoutFor(200L)).isEqualByComparingTo("500000");
    }

    @Test
    void 차감이_netAmount보다_크면_0으로_바닥() {
        Settlement s = mock(Settlement.class);
        when(s.getNetAmount()).thenReturn(new BigDecimal("100000"));
        when(loadSettlementPort.findById(300L)).thenReturn(Optional.of(s));
        when(recordLoanDeductionPort.findDeduction(300L)).thenReturn(Optional.of(new BigDecimal("150000")));

        assertThat(service().netPayoutFor(300L)).isEqualByComparingTo("0");
    }
}
