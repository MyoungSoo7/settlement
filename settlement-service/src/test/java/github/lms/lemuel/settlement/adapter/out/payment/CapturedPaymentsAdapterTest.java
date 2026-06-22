package github.lms.lemuel.settlement.adapter.out.payment;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.application.port.out.LoadCapturedPaymentsPort.CapturedPaymentInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CapturedPaymentsAdapterTest {

    @Mock SettlementPaymentViewRepository paymentViewRepository;
    @InjectMocks CapturedPaymentsAdapter adapter;

    @Test
    @DisplayName("Phase 3: 정산 대상 결제를 로컬 프로젝션(settlement_payment_view)에서 조회·매핑한다 (read-model 미사용)")
    void findCapturedPaymentsByDate_readsLocalProjection() {
        LocalDate date = LocalDate.of(2026, 6, 16);
        SettlementPaymentViewJpaEntity view = new SettlementPaymentViewJpaEntity();
        view.setPaymentId(11L);
        view.setOrderId(22L);
        view.setAmount(new BigDecimal("50000"));
        view.setStatus("CAPTURED");
        view.setCapturedAt(date.atTime(10, 0));

        when(paymentViewRepository.findByCapturedAtBetweenAndStatus(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay(), "CAPTURED"))
                .thenReturn(List.of(view));

        List<CapturedPaymentInfo> result = adapter.findCapturedPaymentsByDate(date);

        assertThat(result).hasSize(1);
        CapturedPaymentInfo info = result.get(0);
        assertThat(info.paymentId()).isEqualTo(11L);
        assertThat(info.orderId()).isEqualTo(22L);
        assertThat(info.amount()).isEqualByComparingTo("50000");
        assertThat(info.capturedAt()).isEqualTo(date.atTime(10, 0));
        // 정산 대상일 = [date 00:00, date+1 00:00) + CAPTURED 만 조회
        verify(paymentViewRepository).findByCapturedAtBetweenAndStatus(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay(), "CAPTURED");
    }
}
