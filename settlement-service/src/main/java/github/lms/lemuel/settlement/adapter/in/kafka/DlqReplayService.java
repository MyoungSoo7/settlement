package github.lms.lemuel.settlement.adapter.in.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DLT 메시지 검사·재처리 서비스.
 *
 * <p>특징:
 * <ul>
 *   <li>장기 컨슈머가 아니라 요청마다 임시 컨슈머 그룹을 만들어 read → seek → close — 장기 lag 발생 X</li>
 *   <li>{@code kafka_dlt-*} 헤더는 제외하고 원본 페이로드/event_id 만 source 토픽으로 다시 publish</li>
 *   <li>이미 컨슈머가 {@code processed_events(group, event_id)} 로 멱등 체크하므로 동일 이벤트 재처리 무해</li>
 *   <li>Replay 한 메시지가 다시 실패해도 또 DLT 로 복귀 — 무한 루프 방지는 운영자 판단(maxMessages 제한)</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);
    private static final String DLT_SUFFIX = ".DLT";
    private static final String DLT_HEADER_PREFIX = "kafka_dlt-";
    /** Replay 시 재시도 회수 카운트 헤더 — 무한 루프 감지에 사용. */
    private static final String REPLAY_COUNT_HEADER = "x-replay-count";

    private final String bootstrapServers;
    private final KafkaTemplate<String, String> dltKafkaTemplate;
    private final Counter replayCounter;

    public DlqReplayService(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                            KafkaTemplate<String, String> dltKafkaTemplate,
                            MeterRegistry meterRegistry) {
        this.bootstrapServers = bootstrapServers;
        this.dltKafkaTemplate = dltKafkaTemplate;
        this.replayCounter = Counter.builder("settlement.kafka.dlt.replayed")
                .description("DLT 에서 원본 토픽으로 replay 된 메시지 수")
                .register(meterRegistry);
    }

    /**
     * DLT 메시지를 검사 (consume 만 하고 commit 하지 않음).
     *
     * @param dltTopic     예: lemuel.payment.captured.DLT
     * @param maxMessages  최대 조회 건수 (1~500)
     * @return 인스펙션 결과 — 페이로드 일부 + 원본 메타 + 예외
     */
    public List<DlqMessage> inspect(String dltTopic, int maxMessages) {
        validateDltTopic(dltTopic);
        int limit = clamp(maxMessages, 1, 500);
        try (Consumer<String, String> consumer = createTransientConsumer("inspect-" + UUID.randomUUID())) {
            assignAndSeekToBeginning(consumer, dltTopic);
            return pollMessages(consumer, limit).stream()
                    .map(DlqMessage::from)
                    .toList();
        }
    }

    /**
     * DLT 메시지를 원본 토픽으로 republish.
     *
     * @return republish 결과 통계
     */
    public ReplayResult replay(String dltTopic, int maxMessages) {
        validateDltTopic(dltTopic);
        int limit = clamp(maxMessages, 1, 500);
        String sourceTopic = dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length());

        int sent = 0;
        int skipped = 0;
        try (Consumer<String, String> consumer = createTransientConsumer("replay-" + UUID.randomUUID())) {
            assignAndSeekToBeginning(consumer, dltTopic);
            List<ConsumerRecord<String, String>> records = pollMessages(consumer, limit);

            for (ConsumerRecord<String, String> r : records) {
                int replayCount = readReplayCount(r);
                if (replayCount >= 5) {
                    log.warn("[DLQ replay] skip — replay limit reached. topic={}, offset={}, replayCount={}",
                            r.topic(), r.offset(), replayCount);
                    skipped++;
                    continue;
                }
                org.apache.kafka.clients.producer.ProducerRecord<String, String> outbound =
                        buildReplayRecord(sourceTopic, r, replayCount + 1);
                dltKafkaTemplate.send(outbound);
                sent++;
            }
        }
        replayCounter.increment(sent);
        log.warn("[DLQ replay] dltTopic={} → sourceTopic={}, sent={}, skipped={}",
                dltTopic, sourceTopic, sent, skipped);
        return new ReplayResult(sourceTopic, dltTopic, sent, skipped);
    }

    private org.apache.kafka.clients.producer.ProducerRecord<String, String> buildReplayRecord(
            String sourceTopic, ConsumerRecord<String, String> dltRecord, int replayCount) {
        org.apache.kafka.clients.producer.ProducerRecord<String, String> out =
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                        sourceTopic, null, dltRecord.key(), dltRecord.value());
        // 원본 헤더는 보존 (event_id, traceparent 등) — DLT 표식 헤더만 제거
        for (Header h : dltRecord.headers()) {
            if (!h.key().startsWith(DLT_HEADER_PREFIX) && !h.key().equals(REPLAY_COUNT_HEADER)) {
                out.headers().add(h);
            }
        }
        out.headers().add(new RecordHeader(REPLAY_COUNT_HEADER,
                String.valueOf(replayCount).getBytes(StandardCharsets.UTF_8)));
        return out;
    }

    private int readReplayCount(ConsumerRecord<String, String> r) {
        Header h = r.headers().lastHeader(REPLAY_COUNT_HEADER);
        if (h == null) return 0;
        try {
            return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Consumer<String, String> createTransientConsumer(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // 일회용 그룹 — 매번 새 ID 로 분리, commit 하지 않으므로 lag 누적 X
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
    }

    private static void assignAndSeekToBeginning(Consumer<String, String> consumer, String topic) {
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        List<TopicPartition> tps = new ArrayList<>();
        for (PartitionInfo p : partitions) {
            tps.add(new TopicPartition(topic, p.partition()));
        }
        consumer.assign(tps);
        consumer.seekToBeginning(tps);
    }

    private static List<ConsumerRecord<String, String>> pollMessages(
            Consumer<String, String> consumer, int limit) {
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5_000L; // 최대 5초 대기
        while (result.size() < limit && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (records.isEmpty()) {
                // 더 들어올 가능성 낮음 — 빈 응답 1회면 종료
                break;
            }
            for (ConsumerRecord<String, String> r : records) {
                if (result.size() >= limit) break;
                result.add(r);
            }
        }
        return result;
    }

    private static void validateDltTopic(String topic) {
        if (topic == null || !topic.endsWith(DLT_SUFFIX)) {
            throw new IllegalArgumentException("Topic must end with " + DLT_SUFFIX + ": " + topic);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record DlqMessage(
            String topic, int partition, long offset,
            String key, String valuePreview,
            String originalTopic, Long originalOffset,
            String exceptionFqcn, String exceptionCauseFqcn, String exceptionMessage,
            String eventId, int replayCount) {

        private static final int VALUE_PREVIEW_LIMIT = 500;

        /**
         * Spring Kafka 의 DeadLetterPublishingRecoverer 는 immediate exception (보통
         * {@code ListenerExecutionFailedException} 래퍼) 을 {@code exception-fqcn} 에,
         * 실제 도메인 예외 cause 를 {@code exception-cause-fqcn} 에 넣는다.
         * 운영자에게 actionable 한 정보는 cause 쪽이므로 둘 다 노출.
         */
        static DlqMessage from(ConsumerRecord<String, String> r) {
            String value = r.value() == null ? null
                    : (r.value().length() > VALUE_PREVIEW_LIMIT
                        ? r.value().substring(0, VALUE_PREVIEW_LIMIT) + "..."
                        : r.value());
            return new DlqMessage(
                    r.topic(), r.partition(), r.offset(),
                    r.key(), value,
                    headerString(r, "kafka_dlt-original-topic"),
                    headerLong(r, "kafka_dlt-original-offset"),
                    headerString(r, "kafka_dlt-exception-fqcn"),
                    headerString(r, "kafka_dlt-exception-cause-fqcn"),
                    headerString(r, "kafka_dlt-exception-message"),
                    headerString(r, "event_id"),
                    headerIntOrZero(r, "x-replay-count"));
        }

        private static String headerString(ConsumerRecord<String, String> r, String name) {
            Header h = r.headers().lastHeader(name);
            return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
        }

        private static Long headerLong(ConsumerRecord<String, String> r, String name) {
            String v = headerString(r, name);
            try {
                return v == null ? null : Long.parseLong(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static int headerIntOrZero(ConsumerRecord<String, String> r, String name) {
            String v = headerString(r, name);
            try {
                return v == null ? 0 : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    public record ReplayResult(String sourceTopic, String dltTopic, int sent, int skipped) { }
}
