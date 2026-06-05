package github.lms.lemuel.common.outbox.application.service;

import github.lms.lemuel.common.outbox.application.port.out.LoadOutboxEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishDlqEventPort;
import github.lms.lemuel.common.outbox.application.port.out.PublishExternalEventPort;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 스케줄러의 DLQ 트리거 검증.
 *
 * <p>핵심 동작: outbox 이벤트가 retryCount >= 10 으로 PENDING → FAILED 전이된 그 시점에
 * 정확히 한 번 {@link PublishDlqEventPort#publishToDlq} 가 호출되어야 한다.
 * 이미 FAILED 상태로 다시 진입하더라도 폴러는 PENDING 만 가져오므로 중복 발행 없음.
 */
class OutboxPublisherSchedulerDlqTest {

    private LoadOutboxEventPort loadPort;
    private SaveOutboxEventPort savePort;
    private PublishExternalEventPort publishPort;
    private PublishDlqEventPort dlqPort;
    private OutboxSingleEventPublisher singleEventPublisher;

    @BeforeEach
    void setup() {
        loadPort = mock(LoadOutboxEventPort.class);
        savePort = mock(SaveOutboxEventPort.class);
        publishPort = mock(PublishExternalEventPort.class);
        dlqPort = mock(PublishDlqEventPort.class);
        singleEventPublisher = new OutboxSingleEventPublisher(savePort, publishPort, dlqPort,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("retryCount 가 10 에 도달하는 발행 시도에서 DLQ 로 발행되고 FAILED 로 전이된다")
    void dlqPublishOnRetryLimit() {
        // 9번 실패 누적 (아직 PENDING)
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        for (int i = 0; i < 9; i++) {
            event.markFailed("prior " + i);
        }
        assertThat(event.getStatus().name()).isEqualTo("PENDING");

        // 10번째 시도가 실패 — markFailed 가 FAILED 로 전이시킴
        doThrow(new RuntimeException("PG down")).when(publishPort).publish(any());

        try {
            singleEventPublisher.publish(event);
        } catch (RuntimeException ignored) {
            // REQUIRES_NEW 트랜잭션 롤백을 시뮬레이트하기 위해 예외가 위로 전파됨 — 정상
        }

        // FAILED 전이 시점에 DLQ 발행 정확히 1회
        verify(dlqPort, times(1)).publishToDlq(event);
        assertThat(event.isFailed()).isTrue();
    }

    @Test
    @DisplayName("일반 PENDING 이벤트가 발행 성공하면 DLQ 는 호출되지 않는다")
    void noDlqOnSuccess() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");

        singleEventPublisher.publish(event);

        verify(dlqPort, never()).publishToDlq(any());
        assertThat(event.getStatus().name()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("retryCount 가 한계 미만이면 markFailed 만 하고 DLQ 발행 없음")
    void noDlqBelowThreshold() {
        OutboxEvent event = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        doThrow(new RuntimeException("transient")).when(publishPort).publish(any());

        try {
            singleEventPublisher.publish(event);
        } catch (RuntimeException ignored) { }

        verify(dlqPort, never()).publishToDlq(any());
        assertThat(event.isFailed()).isFalse();
        assertThat(event.getRetryCount()).isEqualTo(1);
    }
}
