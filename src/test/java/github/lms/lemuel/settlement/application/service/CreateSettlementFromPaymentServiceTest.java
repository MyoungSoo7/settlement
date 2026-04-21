package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSettlementFromPaymentServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @InjectMocks CreateSettlementFromPaymentService service;

    @Test @DisplayName("정산 생성 성공") void create() {
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(result.getPaymentAmount()).isEqualByComparingTo("50000");
        verify(saveSettlementPort).save(any());
    }
    @Test @DisplayName("중복 생성 시 기존 반환 (멱등성)") void create_idempotent() {
        Settlement existing = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(existing));
        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));
        assertThat(result).isSameAs(existing);
        verify(saveSettlementPort, never()).save(any());
    }
}
