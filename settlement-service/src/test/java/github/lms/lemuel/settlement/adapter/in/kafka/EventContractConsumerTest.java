package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementProductViewRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — shared-common 의 <b>정본 샘플 페이로드</b>를 실제 컨슈머
 * 파싱 코드에 통과시켜, 계약이 약속한 값이 UseCase 까지 정확히 전달되는지 검증한다.
 * 프로듀서 계약 테스트(발행 어댑터 → 같은 스키마)와 한 쌍으로, 필드명·타입 드리프트가
 * 런타임(DLT/무성 null)이 아닌 빌드 시점에 드러난다.
 */
@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    @Mock CreateSettlementFromPaymentUseCase createSettlementUseCase;
    @Mock AdjustSettlementForRefundUseCase adjustUseCase;
    @Mock ApplyLoanDeductionUseCase applyLoanDeductionUseCase;
    @Mock LoadSettlementPort loadSettlementPort;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock SettlementPaymentViewRepository paymentViewRepository;
    @Mock SettlementOrderViewRepository orderViewRepository;
    @Mock SettlementUserViewRepository userViewRepository;
    @Mock SettlementProductViewRepository productViewRepository;
    @Mock SettlementProjectionMetrics projectionMetrics;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("payment.captured 정본 샘플 → 정산 생성 UseCase 에 계약 값 그대로 전달된다")
    void paymentCapturedSample_flowsIntoCreateSettlement() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(paymentViewRepository.findById(anyLong())).thenReturn(Optional.empty());
        PaymentEventKafkaConsumer consumer = new PaymentEventKafkaConsumer(
                createSettlementUseCase, processedEventRepository, paymentViewRepository,
                objectMapper, projectionMetrics, null, 0L);

        String sample = EventContractValidator.canonicalSample("lemuel.payment.captured");
        consumer.onPaymentCaptured(recordOf("lemuel.payment.captured", sample), mock(Acknowledgment.class));

        // 결제 시각(capturedAt)까지 정산 생성 UseCase 로 전달돼 정산일이 결제일 기준으로 계산된다.
        verify(createSettlementUseCase).createSettlementFromPayment(
                1001L, 5001L, new BigDecimal("45000"), 777L, "VIP", "T_PLUS_3",
                LocalDateTime.parse("2026-07-01T10:15:30"));
        verify(paymentViewRepository).save(any());
    }

    @Test
    @DisplayName("payment.refunded 정본 샘플 → 역정산 UseCase 에 건별 delta·refundId 가 전달된다")
    void paymentRefundedSample_flowsIntoAdjustSettlement() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(adjustUseCase.adjustSettlementForRefund(eq(1001L), any(), eq(42L)))
                .thenReturn(mock(Settlement.class));
        PaymentRefundedSettlementAdjustConsumer consumer = new PaymentRefundedSettlementAdjustConsumer(
                adjustUseCase, loadSettlementPort, processedEventRepository, objectMapper, null);

        String sample = EventContractValidator.canonicalSample("lemuel.payment.refunded");
        consumer.onPaymentRefunded(recordOf("lemuel.payment.refunded", sample), mock(Acknowledgment.class));

        verify(adjustUseCase).adjustSettlementForRefund(1001L, new BigDecimal("5000"), 42L);
    }

    @Test
    @DisplayName("loan.repayment_applied 정본 샘플 → 대출 차감 UseCase 에 계약 값 그대로 전달된다")
    void loanRepaymentAppliedSample_flowsIntoApplyLoanDeduction() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        LoanRepaymentAppliedConsumer consumer = new LoanRepaymentAppliedConsumer(
                applyLoanDeductionUseCase, processedEventRepository, objectMapper, null);

        String sample = EventContractValidator.canonicalSample("lemuel.loan.repayment_applied");
        consumer.onLoanRepaymentApplied(recordOf("lemuel.loan.repayment_applied", sample), mock(Acknowledgment.class));

        verify(applyLoanDeductionUseCase).apply(9001L, 777L, new BigDecimal("10000"));
    }

    @Test
    @DisplayName("order.created 정본 샘플 → settlement_order_view 에 계약 값 그대로 적재된다")
    void orderCreatedSample_flowsIntoOrderView() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderViewRepository.findById(anyLong())).thenReturn(Optional.empty());
        OrderEventKafkaConsumer consumer = new OrderEventKafkaConsumer(
                orderViewRepository, processedEventRepository, objectMapper, projectionMetrics, null);

        String sample = EventContractValidator.canonicalSample("lemuel.order.created");
        consumer.onOrderCreated(recordOf("lemuel.order.created", sample), mock(Acknowledgment.class));

        ArgumentCaptor<SettlementOrderViewJpaEntity> captor =
                ArgumentCaptor.forClass(SettlementOrderViewJpaEntity.class);
        verify(orderViewRepository).save(captor.capture());
        SettlementOrderViewJpaEntity view = captor.getValue();
        assertThat(view.getOrderId()).isEqualTo(5001L);
        assertThat(view.getUserId()).isEqualTo(301L);
        assertThat(view.getProductId()).isEqualTo(42L);
        assertThat(view.getStatus()).isEqualTo("CREATED");
        assertThat(view.getAmount()).isEqualByComparingTo(new BigDecimal("45000"));
        assertThat(view.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 15));
    }

    @Test
    @DisplayName("user.registered 정본 샘플 → settlement_user_view 에 계약 값 그대로 적재된다")
    void userRegisteredSample_flowsIntoUserView() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(userViewRepository.findById(anyLong())).thenReturn(Optional.empty());
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(
                userViewRepository, processedEventRepository, objectMapper, projectionMetrics, null);

        String sample = EventContractValidator.canonicalSample("lemuel.user.registered");
        consumer.onUserRegistered(recordOf("lemuel.user.registered", sample), mock(Acknowledgment.class));

        ArgumentCaptor<SettlementUserViewJpaEntity> captor =
                ArgumentCaptor.forClass(SettlementUserViewJpaEntity.class);
        verify(userViewRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(301L);
        assertThat(captor.getValue().getEmail()).isEqualTo("seller777@lemuel.io");
    }

    @Test
    @DisplayName("product.changed 정본 샘플 → settlement_product_view 에 계약 값 그대로 적재된다")
    void productChangedSample_flowsIntoProductView() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(productViewRepository.findById(anyLong())).thenReturn(Optional.empty());
        ProductEventKafkaConsumer consumer = new ProductEventKafkaConsumer(
                productViewRepository, processedEventRepository, objectMapper, projectionMetrics, null);

        String sample = EventContractValidator.canonicalSample("lemuel.product.changed");
        consumer.onProductChanged(recordOf("lemuel.product.changed", sample), mock(Acknowledgment.class));

        ArgumentCaptor<SettlementProductViewJpaEntity> captor =
                ArgumentCaptor.forClass(SettlementProductViewJpaEntity.class);
        verify(productViewRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(42L);
        assertThat(captor.getValue().getName()).isEqualTo("프리미엄 원두 1kg");
    }
}
