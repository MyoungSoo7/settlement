package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLT 목적지 결정 규칙 검증.
 *
 * <p>이 규칙이 틀어져도 컨텍스트는 정상 기동하고 테스트는 초록으로 남는다 — 어긋난 사실은
 * 운영에서 "DLT 에 넣었는데 어디에도 없다"(존재하지 않는 파티션) 또는 {@code .DLT.DLT} 증식으로
 * 뒤늦게 드러난다. 그래서 배선 테스트와 별도로 목적지 계산 자체를 고정한다.
 */
class DltDestinationResolverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Counter published = Counter.builder("test.kafka.dlt.published").register(registry);
    private final DltDestinationResolver resolver = new DltDestinationResolver(published);

    private static ConsumerRecord<String, String> record(String topic, int partition) {
        return new ConsumerRecord<>(topic, partition, 0L, "key", "value");
    }

    @Test
    @DisplayName("<원본>.DLT 의 같은 파티션으로 보낸다 — key 기반 순서가 replay 에서도 유지되어야 한다")
    void routesToDltPreservingPartition() {
        TopicPartition destination = resolver.apply(
                record("lemuel.payment.captured", 2), new IllegalStateException("boom"));

        assertThat(destination).isEqualTo(new TopicPartition("lemuel.payment.captured.DLT", 2));
    }

    @Test
    @DisplayName("이미 .DLT 인 토픽은 다시 접미하지 않는다 — .DLT.DLT 무한 증식 방지")
    void doesNotSuffixDltTwice() {
        TopicPartition destination = resolver.apply(
                record("lemuel.payment.captured.DLT", 0), new IllegalStateException("boom"));

        assertThat(destination.topic()).isEqualTo("lemuel.payment.captured.DLT");
    }

    @Test
    @DisplayName("격리 1건마다 카운터가 오른다 — alert-rules.yml 의 DLT 알람 임계가 이 값을 본다")
    void countsEveryQuarantinedRecord() {
        resolver.apply(record("lemuel.order.created", 0), new IllegalStateException("boom"));
        resolver.apply(record("lemuel.order.created", 1), new IllegalArgumentException("bad payload"));

        assertThat(published.count()).isEqualTo(2.0d);
    }
}
