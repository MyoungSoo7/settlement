package github.lms.lemuel.common.outbox.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    @DisplayName("pending 팩토리는 status=PENDING, retryCount=0, publishedAt=null, 새 eventId 를 생성한다")
    void pendingFactoryDefaults() {
        OutboxEvent e = OutboxEvent.pending("Payment", "42", "PaymentCaptured", "{\"paymentId\":42}");

        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(e.getRetryCount()).isZero();
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getLastError()).isNull();
        assertThat(e.getEventId()).isNotNull();
        assertThat(e.getCreatedAt()).isNotNull();
        assertThat(e.isPending()).isTrue();
    }

    @Test
    @DisplayName("markPublished 는 status 를 PUBLISHED 로, publishedAt 을 현재로 세팅하고 lastError 를 지운다")
    void markPublished() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        e.markFailed("transient");

        e.markPublished();

        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getLastError()).isNull();
    }

    @Test
    @DisplayName("markFailed 는 retryCount 를 증가시키고 lastError 를 기록한다. 10회 초과 시 FAILED 전이")
    void markFailedTransitionsToFailedAfterThreshold() {
        OutboxEvent e = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");

        for (int i = 0; i < 9; i++) {
            e.markFailed("boom " + i);
        }
        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(e.getRetryCount()).isEqualTo(9);

        e.markFailed("final");
        assertThat(e.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(e.getRetryCount()).isEqualTo(10);
        assertThat(e.getLastError()).isEqualTo("final");
    }

    @Test
    @DisplayName("같은 페이로드로 두 번 생성한 이벤트는 서로 다른 eventId 를 가진다 (UUID 충돌 없음)")
    void uniqueEventIds() {
        OutboxEvent a = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");
        OutboxEvent b = OutboxEvent.pending("Payment", "1", "PaymentCaptured", "{}");

        assertThat(a.getEventId()).isNotEqualTo(b.getEventId());
    }
}
