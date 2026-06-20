package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetSettlementServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @InjectMocks GetSettlementService service;

    @Test @DisplayName("ID로 정산 조회 성공")
    void getById_success() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(loadSettlementPort.findById(1L)).thenReturn(Optional.of(s));

        Settlement result = service.getSettlementById(1L);
        assertThat(result.getPaymentAmount()).isEqualByComparingTo("50000");
    }

    @Test @DisplayName("ID로 정산 조회 실패")
    void getById_notFound() {
        when(loadSettlementPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSettlementById(999L))
                .isInstanceOf(SettlementNotFoundException.class);
    }

    @Test @DisplayName("결제ID로 정산 조회 — 존재")
    void getByPaymentId_found() {
        Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("20000"), LocalDate.now());
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(s));

        List<Settlement> result = service.getSettlementsByPaymentId(1L);
        assertThat(result).hasSize(1);
    }

    @Test @DisplayName("결제ID로 정산 조회 — 미존재")
    void getByPaymentId_notFound() {
        when(loadSettlementPort.findByPaymentId(999L)).thenReturn(Optional.empty());

        List<Settlement> result = service.getSettlementsByPaymentId(999L);
        assertThat(result).isEmpty();
    }
}
