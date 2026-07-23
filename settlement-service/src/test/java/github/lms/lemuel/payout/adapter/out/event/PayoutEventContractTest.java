package github.lms.lemuel.payout.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — settlement 가 account 로 발행하는 payout.completed 이벤트가
 * shared-common 의 계약 스키마를 통과해야 한다. account 의 GL 전기(DR SELLER_PAYABLE / CR CASH)가
 * 이 계약에 의존한다(ADR 0026 Option A 현금 폐루프).
 */
@ExtendWith(MockitoExtension.class)
class PayoutEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    PayoutKafkaEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new PayoutKafkaEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private OutboxEvent saved() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    @Test
    @DisplayName("PayoutCompleted 페이로드는 lemuel.payout.completed 계약을 만족한다")
    void payoutCompleted_satisfiesContract() {
        publisher.publishPayoutCompleted(7001L, 9001L, 777L, new BigDecimal("43425"));

        EventContractValidator.assertValid("lemuel.payout.completed", saved().getPayload());
    }

    @Test
    @DisplayName("settlementId 가 null(수동 송금)이어도 계약을 만족한다")
    void payoutCompleted_nullSettlementId_satisfiesContract() {
        publisher.publishPayoutCompleted(7002L, null, 777L, new BigDecimal("10000.50"));

        EventContractValidator.assertValid("lemuel.payout.completed", saved().getPayload());
    }

    @Test
    @DisplayName("aggregateType=Payout + eventType=PayoutCompleted → 토픽 lemuel.payout.completed 로 유도된다")
    void routesToPayoutCompletedTopic() {
        publisher.publishPayoutCompleted(7003L, 9001L, 777L, new BigDecimal("43425"));

        OutboxEvent event = saved();
        assertThat(event.getAggregateType()).isEqualTo("Payout");
        assertThat(event.getEventType()).isEqualTo("PayoutCompleted");
        assertThat(event.getAggregateId()).isEqualTo("7003");
    }
}
