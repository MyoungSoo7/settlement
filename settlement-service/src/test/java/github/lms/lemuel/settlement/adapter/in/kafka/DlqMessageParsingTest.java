package github.lms.lemuel.settlement.adapter.in.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 메시지 파싱 — 운영자 인스펙션 응답이 정확한 헤더 정보를 노출하는지 검증.
 * Spring Kafka 의 {@code DeadLetterPublishingRecoverer} 가 부여하는 표준 헤더에 의존하므로
 * 헤더 키 변경 시 즉시 깨져야 한다.
 */
class DlqMessageParsingTest {

    @Test
    void from_extracts_dlt_headers_event_id_and_replay_count() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "lemuel.payment.captured.DLT", 0, 42L, "key-1",
                "{\"paymentId\":1,\"orderId\":2,\"amount\":\"1000.00\"}");

        record.headers().add(new RecordHeader("kafka_dlt-original-topic",
                "lemuel.payment.captured".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("kafka_dlt-original-offset",
                "100".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("kafka_dlt-exception-fqcn",
                "org.springframework.kafka.listener.ListenerExecutionFailedException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("kafka_dlt-exception-cause-fqcn",
                "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("kafka_dlt-exception-message",
                "Invalid payload".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event_id",
                "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("x-replay-count",
                "2".getBytes(StandardCharsets.UTF_8)));

        DlqReplayService.DlqMessage msg = DlqReplayService.DlqMessage.from(record);

        assertThat(msg.topic()).isEqualTo("lemuel.payment.captured.DLT");
        assertThat(msg.partition()).isZero();
        assertThat(msg.offset()).isEqualTo(42L);
        assertThat(msg.key()).isEqualTo("key-1");
        assertThat(msg.valuePreview()).contains("paymentId");
        assertThat(msg.originalTopic()).isEqualTo("lemuel.payment.captured");
        assertThat(msg.originalOffset()).isEqualTo(100L);
        assertThat(msg.exceptionFqcn())
                .isEqualTo("org.springframework.kafka.listener.ListenerExecutionFailedException");
        assertThat(msg.exceptionCauseFqcn())
                .isEqualTo("java.lang.IllegalArgumentException");
        assertThat(msg.exceptionMessage()).isEqualTo("Invalid payload");
        assertThat(msg.eventId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(msg.replayCount()).isEqualTo(2);
    }

    @Test
    void from_handles_missing_headers_gracefully() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "topic.DLT", 1, 0L, null, "{}");

        DlqReplayService.DlqMessage msg = DlqReplayService.DlqMessage.from(record);

        assertThat(msg.originalTopic()).isNull();
        assertThat(msg.originalOffset()).isNull();
        assertThat(msg.exceptionFqcn()).isNull();
        assertThat(msg.exceptionCauseFqcn()).isNull();
        assertThat(msg.eventId()).isNull();
        assertThat(msg.replayCount()).isZero();
    }

    @Test
    void from_handles_malformed_offset_header() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic.DLT", 0, 0L, null, "v");
        record.headers().add(new RecordHeader("kafka_dlt-original-offset",
                "not-a-number".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("x-replay-count",
                "garbage".getBytes(StandardCharsets.UTF_8)));

        DlqReplayService.DlqMessage msg = DlqReplayService.DlqMessage.from(record);

        // 잘못된 헤더는 fail-open — null/0 으로 fallback (운영자 인스펙션이 깨지면 안 됨)
        assertThat(msg.originalOffset()).isNull();
        assertThat(msg.replayCount()).isZero();
    }

    @Test
    void from_truncates_large_value_to_preview() {
        String large = "a".repeat(1000);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("topic.DLT", 0, 0L, null, large);

        DlqReplayService.DlqMessage msg = DlqReplayService.DlqMessage.from(record);

        // 인스펙션 응답이 거대 페이로드로 커지지 않도록 — 500자 + "..." 컷
        assertThat(msg.valuePreview()).hasSize(503).endsWith("...");
    }
}
