package github.lms.lemuel.recovery.adapter.out.event;

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
 * 프로듀서 계약 테스트 (ADR 0024) — settlement 가 account 로 발행하는 지급후 회수 채권(SellerRecovery)
 * 이벤트가 shared-common 의 계약 스키마를 통과해야 한다. account 의 GL 전기(ADR 0026 Option ①)가 의존한다.
 *
 * <p>핵심: aggregateType 이 리터럴 "seller_recovery" 여야 라우터가 lemuel.seller_recovery.* 를 만든다
 * (aggregate 세그먼트는 snake 변환되지 않고 소문자화만 됨).
 */
@ExtendWith(MockitoExtension.class)
class SellerRecoveryEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    SellerRecoveryKafkaEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new SellerRecoveryKafkaEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private OutboxEvent saved() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue();
    }

    /** KafkaOutboxPublisher.resolveTopic 복제 — aggregate 는 소문자화만, eventType 은 접두사 제거 후 snake. */
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
    @DisplayName("RecoveryOpened 페이로드는 lemuel.seller_recovery.opened 계약을 만족한다")
    void opened_satisfiesContract() {
        publisher.publishRecoveryOpened(6001L, 777L, new BigDecimal("2000.00"));

        OutboxEvent e = saved();
        assertThat(e.getAggregateType()).isEqualTo("seller_recovery");
        assertThat(e.getEventType()).isEqualTo("Opened");
        assertThat(resolveTopic(e)).isEqualTo("lemuel.seller_recovery.opened");
        EventContractValidator.assertValid("lemuel.seller_recovery.opened", e.getPayload());
    }

    @Test
    @DisplayName("RecoveryOffset 페이로드는 lemuel.seller_recovery.offset 계약을 만족한다")
    void offset_satisfiesContract() {
        publisher.publishRecoveryOffset(7001L, 6001L, 777L, new BigDecimal("500.00"));

        OutboxEvent e = saved();
        assertThat(e.getAggregateType()).isEqualTo("seller_recovery");
        assertThat(e.getEventType()).isEqualTo("Offset");
        assertThat(resolveTopic(e)).isEqualTo("lemuel.seller_recovery.offset");
        EventContractValidator.assertValid("lemuel.seller_recovery.offset", e.getPayload());
    }

    @Test
    @DisplayName("RecoveryOffset(recoveryId null) 페이로드도 계약을 만족한다 — optional 생략")
    void offset_nullRecoveryId_satisfiesContract() {
        publisher.publishRecoveryOffset(7001L, null, 777L, new BigDecimal("500.00"));

        String payload = saved().getPayload();
        assertThat(payload).doesNotContain("recoveryId"); // null 은 생략(계약상 integer, null 불가)
        EventContractValidator.assertValid("lemuel.seller_recovery.offset", payload);
    }
}
