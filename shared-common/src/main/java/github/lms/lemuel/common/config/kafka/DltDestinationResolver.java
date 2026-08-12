package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.Counter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * 재시도 소진 메시지를 보낼 DLT 목적지를 결정한다.
 *
 * <p>규칙: {@code <원본토픽>.DLT} 의 <b>같은 파티션 번호</b>. 파티션을 보존해야 key 기반 순서가
 * replay 시에도 유지된다(그래서 DLT 토픽은 원본과 파티션 수가 같아야 한다).
 *
 * <p>목적지를 정하는 김에 카운터를 올리고 ERROR 로그를 남긴다 — 이 카운터가
 * {@code monitoring/alert-rules.yml} 의 DLT 알람 임계 근거다.
 */
public final class DltDestinationResolver
        implements BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> {

    private static final Logger log = LoggerFactory.getLogger(DltDestinationResolver.class);

    /** Spring Kafka {@code DeadLetterPublishingRecoverer} 기본 명명 규칙. */
    private static final String DLT_SUFFIX = ".DLT";

    private final Counter dltPublished;

    public DltDestinationResolver(Counter dltPublished) {
        this.dltPublished = dltPublished;
    }

    @Override
    public TopicPartition apply(ConsumerRecord<?, ?> record, Exception ex) {
        dltPublished.increment();
        log.error("[DLT] publishing record to DLT. topic={}, partition={}, offset={}, exception={}",
                record.topic(), record.partition(), record.offset(), ex.getClass().getSimpleName());
        return new TopicPartition(dltTopicOf(record.topic()), record.partition());
    }

    /** 이미 {@code .DLT} 인 토픽은 다시 접미하지 않는다 — {@code .DLT.DLT} 무한 증식 방지. */
    private static String dltTopicOf(String topic) {
        return topic.endsWith(DLT_SUFFIX) ? topic : topic + DLT_SUFFIX;
    }
}
