package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementOrderViewRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewRepository;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementSearchDocumentMapperTest {

    @Mock SettlementOrderViewRepository orderViewRepository;
    @Mock SettlementPaymentViewRepository paymentViewRepository;

    SettlementSearchDocumentMapper mapper;

    private Settlement buildSettlement() {
        Settlement settlement = Settlement.createFromPayment(
                404L, 202L, new BigDecimal("100000"), LocalDate.of(2026, 4, 1));
        settlement.assignId(1L);
        return settlement;
    }

    @Test
    @DisplayName("payment/order 프로젝션이 모두 존재하면 통합 문서로 매핑한다")
    void toDocument_mapsBothProjections() {
        mapper = new SettlementSearchDocumentMapper(orderViewRepository, paymentViewRepository);
        Settlement settlement = buildSettlement();

        SettlementPaymentViewJpaEntity payment = new SettlementPaymentViewJpaEntity();
        payment.setPaymentId(404L);
        payment.setOrderId(202L);
        payment.setStatus("CAPTURED");
        payment.setAmount(new BigDecimal("100000"));
        payment.setRefundedAmount(new BigDecimal("0"));
        payment.setPaymentMethod("CARD");
        payment.setPgTransactionId("pg-tx-9");
        payment.setCapturedAt(LocalDateTime.of(2026, 4, 1, 9, 0));
        payment.setUpdatedAt(LocalDateTime.now());

        SettlementOrderViewJpaEntity order = new SettlementOrderViewJpaEntity();
        order.setOrderId(202L);
        order.setUserId(303L);
        order.setProductId(7L);
        order.setStatus("PAID");
        order.setAmount(new BigDecimal("100000"));
        order.setCreatedAt(LocalDateTime.of(2026, 3, 30, 8, 0));
        order.setUpdatedAt(LocalDateTime.now());

        when(paymentViewRepository.findById(404L)).thenReturn(Optional.of(payment));
        when(orderViewRepository.findById(202L)).thenReturn(Optional.of(order));

        SettlementSearchDocument document = mapper.toDocument(settlement);

        assertThat(document.getSettlementId()).isEqualTo(1L);
        assertThat(document.getSettlementStatus()).isEqualTo(settlement.getStatus().name());
        assertThat(document.getSettlementAmount()).isEqualByComparingTo(settlement.getNetAmount());
        assertThat(document.getSettlementDate()).isEqualTo(LocalDate.of(2026, 4, 1));

        assertThat(document.getPaymentId()).isEqualTo(404L);
        assertThat(document.getPaymentStatus()).isEqualTo("CAPTURED");
        assertThat(document.getPaymentAmount()).isEqualByComparingTo("100000");
        assertThat(document.getRefundedAmount()).isEqualByComparingTo("0");
        assertThat(document.getPaymentMethod()).isEqualTo("CARD");
        assertThat(document.getPgTransactionId()).isEqualTo("pg-tx-9");
        assertThat(document.getPaymentCapturedAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 9, 0));

        assertThat(document.getOrderId()).isEqualTo(202L);
        assertThat(document.getUserId()).isEqualTo(303L);
        assertThat(document.getOrderStatus()).isEqualTo("PAID");
        assertThat(document.getOrderAmount()).isEqualByComparingTo("100000");
        assertThat(document.getOrderCreatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 30, 8, 0));

        assertThat(document.getIndexedAt()).isNotNull();
    }

    @Test
    @DisplayName("payment/order 프로젝션이 모두 없으면 경고 로그만 남기고 해당 필드는 비운다")
    void toDocument_missingProjections_leavesFieldsNull() {
        mapper = new SettlementSearchDocumentMapper(orderViewRepository, paymentViewRepository);
        Settlement settlement = buildSettlement();

        when(paymentViewRepository.findById(404L)).thenReturn(Optional.empty());
        when(orderViewRepository.findById(202L)).thenReturn(Optional.empty());

        SettlementSearchDocument document = mapper.toDocument(settlement);

        assertThat(document.getSettlementId()).isEqualTo(1L);
        assertThat(document.getPaymentId()).isNull();
        assertThat(document.getPaymentStatus()).isNull();
        assertThat(document.getOrderId()).isNull();
        assertThat(document.getOrderStatus()).isNull();
        assertThat(document.getIndexedAt()).isNotNull();
    }
}
