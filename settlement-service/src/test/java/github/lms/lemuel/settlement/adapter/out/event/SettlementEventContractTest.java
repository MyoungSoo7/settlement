package github.lms.lemuel.settlement.adapter.out.event;

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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — settlement 가 loan·account 로 발행하는 정산 이벤트가
 * shared-common 의 계약 스키마를 통과해야 한다. loan 의 상환 saga 와 account 의 GL 전기(ADR 0026 Option ①)가
 * 이 계약들에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    SettlementKafkaEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new SettlementKafkaEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private OutboxEvent saved() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    /**
     * KafkaOutboxPublisher.resolveTopic 의 3줄 라우팅을 복제해, aggregateType/eventType 이 정확한 토픽
     * 문자열을 유도하는지 테스트단에서도 못박는다(aggregate 세그먼트는 소문자화만, eventType 은 접두사 제거 후 snake).
     */
    private static String resolveTopic(OutboxEvent e) {
        String aggregate = e.getAggregateType().toLowerCase(java.util.Locale.ROOT);
        String eventType = e.getEventType();
        if (eventType.startsWith(e.getAggregateType())) {
            eventType = eventType.substring(e.getAggregateType().length());
        }
        StringBuilder snake = new StringBuilder(eventType.length() + 4);
        for (int i = 0; i < eventType.length(); i++) {
            char c = eventType.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) snake.append('_');
                snake.append(Character.toLowerCase(c));
            } else {
                snake.append(c);
            }
        }
        return "lemuel." + aggregate + "." + snake;
    }

    @Test
    @DisplayName("SettlementCreated(holdbackAmount 포함) 페이로드는 lemuel.settlement.created 계약을 만족한다")
    void settlementCreated_satisfiesContract() {
        publisher.publishSettlementCreated(9001L, 777L, new BigDecimal("43425"),
                LocalDate.of(2026, 7, 10), new BigDecimal("13027.50"));

        OutboxEvent e = saved();
        assertThat(resolveTopic(e)).isEqualTo("lemuel.settlement.created");
        EventContractValidator.assertValid("lemuel.settlement.created", e.getPayload());
    }

    @Test
    @DisplayName("SettlementCreated(dueDate null) 페이로드도 계약을 만족한다 — null 허용 필드")
    void settlementCreated_nullDueDate_satisfiesContract() {
        publisher.publishSettlementCreated(9001L, 777L, new BigDecimal("43425"), null, BigDecimal.ZERO);

        EventContractValidator.assertValid("lemuel.settlement.created", saved().getPayload());
    }

    @Test
    @DisplayName("SettlementConfirmed 페이로드는 lemuel.settlement.confirmed 계약을 만족한다")
    void settlementConfirmed_satisfiesContract() {
        publisher.publishSettlementConfirmed(9001L, 777L, new BigDecimal("43425"));

        EventContractValidator.assertValid("lemuel.settlement.confirmed", saved().getPayload());
    }

    @Test
    @DisplayName("SettlementHoldbackReleased 페이로드는 lemuel.settlement.holdback_released 계약을 만족한다")
    void holdbackReleased_satisfiesContract() {
        publisher.publishHoldbackReleased(9001L, 777L, new BigDecimal("13027.50"));

        OutboxEvent e = saved();
        assertThat(resolveTopic(e)).isEqualTo("lemuel.settlement.holdback_released");
        EventContractValidator.assertValid("lemuel.settlement.holdback_released", e.getPayload());
    }

    @Test
    @DisplayName("SettlementHoldbackConsumed 페이로드는 lemuel.settlement.holdback_consumed 계약을 만족한다")
    void holdbackConsumed_satisfiesContract() {
        publisher.publishHoldbackConsumed(4001L, 9001L, 777L, new BigDecimal("5000.00"));

        OutboxEvent e = saved();
        assertThat(resolveTopic(e)).isEqualTo("lemuel.settlement.holdback_consumed");
        EventContractValidator.assertValid("lemuel.settlement.holdback_consumed", e.getPayload());
    }

    @Test
    @DisplayName("SettlementHoldbackConsumed(settlementId null) 페이로드도 계약을 만족한다 — optional 생략")
    void holdbackConsumed_nullSettlementId_satisfiesContract() {
        publisher.publishHoldbackConsumed(4001L, null, 777L, new BigDecimal("5000.00"));

        String payload = saved().getPayload();
        assertThat(payload).doesNotContain("settlementId"); // null 은 생략(계약상 integer, null 불가)
        EventContractValidator.assertValid("lemuel.settlement.holdback_consumed", payload);
    }

    @Test
    @DisplayName("SettlementAdjusted 페이로드는 lemuel.settlement.adjusted 계약을 만족한다")
    void adjusted_satisfiesContract() {
        publisher.publishSettlementAdjusted(4002L, 9001L, 777L, new BigDecimal("5450.00"), "SELLER_PAYABLE");

        OutboxEvent e = saved();
        assertThat(resolveTopic(e)).isEqualTo("lemuel.settlement.adjusted");
        EventContractValidator.assertValid("lemuel.settlement.adjusted", e.getPayload());
    }

    @Test
    @DisplayName("SettlementCanceled 페이로드는 lemuel.settlement.canceled 계약을 만족한다")
    void canceled_satisfiesContract() {
        publisher.publishSettlementCanceled(9001L, 777L, new BigDecimal("30000.00"), new BigDecimal("13027.50"));

        OutboxEvent e = saved();
        assertThat(resolveTopic(e)).isEqualTo("lemuel.settlement.canceled");
        EventContractValidator.assertValid("lemuel.settlement.canceled", e.getPayload());
    }
}
