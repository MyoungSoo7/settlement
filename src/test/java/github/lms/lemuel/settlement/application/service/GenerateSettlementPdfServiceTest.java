package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.GetSettlementUseCase;
import github.lms.lemuel.settlement.application.port.out.SettlementPdfPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerateSettlementPdfServiceTest {

    @Mock GetSettlementUseCase getSettlementUseCase;
    @Mock SettlementPdfPort pdfPort;
    @InjectMocks GenerateSettlementPdfService service;

    @Test @DisplayName("PDF 생성 성공") void generate() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(getSettlementUseCase.getSettlementById(1L)).thenReturn(s);
        when(pdfPort.render(s)).thenReturn(new byte[]{0x25, 0x50, 0x44, 0x46});

        byte[] result = service.generate(1L);

        assertThat(result).isNotEmpty();
        verify(pdfPort).render(s);
    }
}
