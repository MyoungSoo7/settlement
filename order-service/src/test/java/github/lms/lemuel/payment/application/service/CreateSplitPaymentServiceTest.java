package github.lms.lemuel.payment.application.service;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase.TenderRequest;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateSplitPaymentService — 분할결제 생성")
class CreateSplitPaymentServiceTest {

    @Mock PgClientPort pgClientPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    @InjectMocks CreateSplitPaymentService service;

    @Test
    @DisplayName("외부 PG + 내부 잔액 tender 혼합 → CAPTURED 저장 + 주문 PAID + 이벤트 발행")
    void createSplit_mixedTenders() {
        when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("PGTX-1");
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any()))
                .thenReturn(Optional.of(new SellerSettlementMeta(9L, "VIP", "T+3")));

        PaymentDomain result = service.createSplit(100L, List.of(
                new TenderRequest(TenderType.CARD, new BigDecimal("35000")),
                new TenderRequest(TenderType.POINT, new BigDecimal("5000"))));

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(result.getAmount()).isEqualByComparingTo("40000");
        assertThat(result.getTenders()).hasSize(2);
        verify(pgClientPort).authorize(eq(100L), any(), eq("CARD"));
        verify(pgClientPort).capture(eq("PGTX-1"), any());
        verify(updateOrderStatusPort).updateOrderStatus(100L, "PAID");
        verify(publishEventPort).publishPaymentCaptured(any(), eq(100L), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("모두 내부 잔액 tender 면 PG 호출 없음")
    void createSplit_allInternal() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        PaymentDomain result = service.createSplit(200L, List.of(
                new TenderRequest(TenderType.POINT, new BigDecimal("3000")),
                new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("2000"))));

        assertThat(result.getAmount()).isEqualByComparingTo("5000");
        verify(pgClientPort, never()).authorize(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("tender 1개면 예외")
    void createSplit_tooFew() {
        assertThatThrownBy(() -> service.createSplit(1L,
                List.of(new TenderRequest(TenderType.CARD, new BigDecimal("100")))))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("tender null 이면 예외")
    void createSplit_null() {
        assertThatThrownBy(() -> service.createSplit(1L, null))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }
}
