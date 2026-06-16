package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase.IngestSettlementCommand;
import github.lms.lemuel.loan.application.port.out.SaveSettlementViewPort;
import github.lms.lemuel.loan.domain.SellerSettlementView;
import github.lms.lemuel.loan.domain.SettlementViewStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestSettlementServiceTest {

    @Mock SaveSettlementViewPort saveSettlementViewPort;
    @InjectMocks IngestSettlementService service;

    @Test
    void 커맨드를_PENDING_정산뷰로_매핑해_upsert한다() {
        IngestSettlementCommand command = new IngestSettlementCommand(
                100L, 7L, new BigDecimal("500000"), LocalDate.of(2026, 6, 22));

        service.ingest(command);

        ArgumentCaptor<SellerSettlementView> captor = ArgumentCaptor.forClass(SellerSettlementView.class);
        verify(saveSettlementViewPort).upsert(captor.capture());

        SellerSettlementView saved = captor.getValue();
        assertThat(saved.getSettlementId()).isEqualTo(100L);
        assertThat(saved.getSellerId()).isEqualTo(7L);
        assertThat(saved.getAmount()).isEqualByComparingTo("500000");
        assertThat(saved.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(saved.getStatus()).isEqualTo(SettlementViewStatus.PENDING);
    }
}
