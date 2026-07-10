package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase.IngestConfirmedSettlementCommand;
import github.lms.lemuel.investment.application.port.out.SaveFundingViewPort;
import github.lms.lemuel.investment.domain.FundingViewStatus;
import github.lms.lemuel.investment.domain.SellerFundingView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestConfirmedSettlementServiceTest {

    @Mock SaveFundingViewPort saveFundingViewPort;

    @Test
    void 확정정산금을_CONFIRMED_재원으로_upsert한다() {
        IngestConfirmedSettlementService service = new IngestConfirmedSettlementService(saveFundingViewPort);

        service.ingest(new IngestConfirmedSettlementCommand(9001L, 777L, new BigDecimal("43425")));

        ArgumentCaptor<SellerFundingView> captor = ArgumentCaptor.forClass(SellerFundingView.class);
        verify(saveFundingViewPort).upsert(captor.capture());
        SellerFundingView view = captor.getValue();
        assertThat(view.getSettlementId()).isEqualTo(9001L);
        assertThat(view.getSellerId()).isEqualTo(777L);
        assertThat(view.getAmount()).isEqualByComparingTo("43425");
        assertThat(view.getStatus()).isEqualTo(FundingViewStatus.CONFIRMED);
    }
}
