package github.lms.lemuel.common.outbox.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 멱등 Kafka 이벤트 컨슈머의 공통 골격(Template Method).
 *
 * <p>모든 이벤트 컨슈머가 반복하던 다음 흐름을 한 곳에 고정한다 —
 * {@code event_id} 헤더 추출 → {@code processed_events(consumer_group, event_id)} 멱등 체크 →
 * JSON 파싱(실패 시 예외 → DLT) → {@link #handle} 위임 → 멱등 마커 저장 → ack.
 * 서브클래스는 {@link #consumerGroup()} / {@link #eventType()} / {@link #handle} 세 훅만 채운다.
 *
 * <p>3단 멱등 방어의 2단계(processed_events PK)를 구조적으로 강제해, 새 컨슈머를 추가할 때
 * 멱등 preamble 을 빠뜨리는 실수를 원천 차단한다. 서브클래스는 자신의
 * {@code @KafkaListener}/{@code @Transactional} 메서드에서 {@link #consume}만 호출하면 된다
 * (리스너 토픽·그룹·트랜잭션 경계는 컨슈머별로 유지).
 *
 * <p><b>비대상:</b> practice-delay 토글·순서역전 처리 등 컨슈머 고유 로직이 큰 경우는
 * {@link #handle} 안에서 처리하거나 이 골격을 상속하지 않는다(억지로 끼워 맞추지 않는다).
 */
public abstract class IdempotentEventConsumer {

    /** 서브클래스 이름으로 로거를 생성해 로그 출처를 원래 컨슈머와 동일하게 유지한다. */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    protected IdempotentEventConsumer(ProcessedEventRepository processedEventRepository,
                                      ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    /** 멱등 키·마커에 쓰이는 컨슈머 그룹 식별자. */
    protected abstract String consumerGroup();

    /** {@code processed_events.event_type} 에 기록할 이벤트 명(관측/디버깅용). */
    protected abstract String eventType();

    /**
     * 실제 도메인 처리(프로젝션 upsert 또는 use case 호출)를 수행한다.
     * 멱등 체크를 통과하고 JSON 파싱에 성공한 뒤에만 호출된다.
     *
     * @param payload 파싱된 이벤트 본문
     * @param eventId 멱등 키(로그·검증 메시지에 사용)
     */
    protected abstract void handle(JsonNode payload, UUID eventId);

    /**
     * 멱등 마커 저장 직후·ack 직전에 호출되는 사후 훅. 기본 no-op.
     * 프로젝션 지연 메트릭 기록 등 "정상 처리 1건"에 종속된 부수효과에 사용한다.
     */
    protected void afterProcessed(ConsumerRecord<String, String> record) {
        // no-op by default
    }

    /**
     * 공통 멱등 처리 골격. 서브클래스의 {@code @KafkaListener} 메서드가 그대로 위임한다.
     */
    protected final void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("event_id 헤더 없는 레코드 스킵. topic={}, offset={}", record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ProcessedEventJpaEntity.ProcessedEventId key =
                new ProcessedEventJpaEntity.ProcessedEventId(consumerGroup(), eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("이미 처리된 이벤트 스킵. group={}, eventId={}", consumerGroup(), eventId);
            ack.acknowledge();
            return;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(record.value());
        } catch (JsonProcessingException e) {
            // 파싱 실패는 재시도로 복구 불가 → 원문을 진단 로그로 남기고 예외를 던져 DLT 로 보낸다.
            log.error("잘못된 JSON payload (DLT 대상). eventId={}, payload={}", eventId, record.value());
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        handle(payload, eventId);

        processedEventRepository.save(new ProcessedEventJpaEntity(consumerGroup(), eventId, eventType()));
        afterProcessed(record);
        ack.acknowledge();
    }

    /**
     * 필수 필드 접근 헬퍼 — 누락/null 은 재시도로 복구 불가한 계약 위반이므로
     * {@link IllegalArgumentException}(에러핸들러 non-retryable)을 던져 즉시 DLT 로 격리한다.
     * 무검증 {@code node.get(...).asText()} 는 NPE 로 새어 재시도를 낭비하므로 이 헬퍼를 쓴다.
     */
    protected static JsonNode required(JsonNode payload, String field, UUID eventId) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("필수 필드 누락: " + field + ", eventId=" + eventId);
        }
        return value;
    }

    protected static String requiredText(JsonNode payload, String field, UUID eventId) {
        return required(payload, field, eventId).asText();
    }

    protected static long requiredLong(JsonNode payload, String field, UUID eventId) {
        return required(payload, field, eventId).asLong();
    }

    /** 금액 필드 — 숫자가 아니면 {@link NumberFormatException}(IAE 하위) → 역시 즉시 DLT. */
    protected static BigDecimal requiredDecimal(JsonNode payload, String field, UUID eventId) {
        return new BigDecimal(required(payload, field, eventId).asText());
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
