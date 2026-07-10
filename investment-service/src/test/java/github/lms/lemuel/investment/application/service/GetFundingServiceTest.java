package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.domain.SellerFunding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetFundingServiceTest {

    @Mock LoadFundingViewPort loadFundingViewPort;
    @Mock LoadInvestmentOrderPort loadInvestmentOrderPort;

    @Test
    void 확정재원에서_집행합을_뺀_가용액을_반환한다() {
        when(loadFundingViewPort.sumConfirmedBySeller(7L)).thenReturn(new BigDecimal("1000000"));
        when(loadInvestmentOrderPort.sumExecutedAmountBySeller(7L)).thenReturn(new BigDecimal("300000"));

        SellerFunding funding = new GetFundingService(loadFundingViewPort, loadInvestmentOrderPort).getFunding(7L);

        assertThat(funding.sellerId()).isEqualTo(7L);
        assertThat(funding.confirmedTotal()).isEqualByComparingTo("1000000");
        assertThat(funding.investedTotal()).isEqualByComparingTo("300000");
        assertThat(funding.available()).isEqualByComparingTo("700000");
    }
}
