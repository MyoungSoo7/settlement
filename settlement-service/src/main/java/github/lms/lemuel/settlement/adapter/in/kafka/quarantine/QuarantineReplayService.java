package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 격리 이벤트 재처리 — 수정된 payload(선택)와 유효한 event_id 로 원본 토픽에 republish (P0-3 AC3).
 *
 * <p>{@code DlqReplayService} 와 같은 원리: 재처리는 소비 경로를 그대로 다시 태우고,
 * 정확히-한-번은 {@code processed_events} 멱등이 보장한다. 재처리에 사용한 event_id 를
 * 격리 행에 남기고 {@code NEW → REPLAYED} 로 전이한다(전이는 엔티티 메서드가 강제).
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class QuarantineReplayService {

    private final QuarantinedEventRepository quarantinedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public QuarantineReplayService(QuarantinedEventRepository quarantinedEventRepository,
                                   KafkaTemplate<String, String> kafkaTemplate) {
        this.quarantinedEventRepository = quarantinedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** 브로커 ack 대기 상한 — 초과 시 전이 없이 실패시켜 재시도 가능 상태(NEW)를 보존한다. */
    private static final long SEND_ACK_TIMEOUT_SECONDS = 10;

    /**
     * 격리된 <b>원본 payload</b> 그대로 원본 토픽에 republish 한다. payload override 는 지원하지 않는다 —
     * 운영자가 임의 payload 로 상류 소유 토픽에 이벤트를 위조하는 벡터를 원천 차단한다. event_id 부재
     * 격리(MISSING/INVALID)는 서버가 결정적 UUID 를 부여하므로 원본 그대로도 정상 복구된다.
     *
     * @param quarantineId 격리 행 id (NEW 상태여야 함)
     * @return 재처리에 사용된 event_id
     */
    @Transactional
    public UUID replay(long quarantineId) {
        // 행 잠금(PESSIMISTIC_WRITE)으로 동시 replay 를 직렬화 — 두 번째 호출은 REPLAYED 를 보고 거부된다.
        QuarantinedEventJpaEntity row = quarantinedEventRepository.findByIdForUpdate(quarantineId)
                .orElseThrow(() -> new IllegalArgumentException("격리 이벤트 없음: id=" + quarantineId));
        if (row.getStatus() != QuarantinedEventJpaEntity.Status.NEW) {
            throw new IllegalStateException("이미 재처리된 격리 이벤트: id=" + quarantineId + ", status=" + row.getStatus());
        }

        // event_id 부재 시 행 id 기반 결정적 UUID — 재시도·경쟁 호출이 같은 id 를 재생성해
        // processed_events 멱등에 흡수된다(랜덤이면 재시도마다 "새 이벤트"가 되어 이중 반영).
        UUID eventId = row.getEventId() != null
                ? row.getEventId()
                : UUID.nameUUIDFromBytes(("quarantine-replay:" + quarantineId).getBytes(StandardCharsets.UTF_8));

        ProducerRecord<String, String> outbound = new ProducerRecord<>(row.getTopic(), null, row.getPayload());
        outbound.headers().add(new RecordHeader("event_id",
                eventId.toString().getBytes(StandardCharsets.UTF_8)));

        // 브로커 ack 를 확인한 뒤에만 REPLAYED 전이 — fire-and-forget 이면 전송 유실이
        // "재처리 완료"로 둔갑한다(P0-3 이 없애려는 조용한 유실). 실패 시 tx 롤백 → 행은 NEW 유지.
        try {
            kafkaTemplate.send(outbound).get(SEND_ACK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("격리 재처리 발행 중단: id=" + quarantineId, e);
        } catch (Exception e) {
            throw new IllegalStateException("격리 재처리 발행 실패(행은 NEW 유지, 재시도 가능): id=" + quarantineId, e);
        }

        row.markReplayed(eventId);
        quarantinedEventRepository.save(row);
        return eventId;
    }
}
