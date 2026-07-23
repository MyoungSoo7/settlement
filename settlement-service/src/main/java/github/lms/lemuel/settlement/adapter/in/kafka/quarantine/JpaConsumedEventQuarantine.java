package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * settlement 의 격리 추적 구현 (P0-3) — {@link ConsumedEventQuarantine} 계약 이행.
 *
 * <p>REQUIRES_NEW: 컨슈머가 격리 기록 후 예외를 rethrow(DLT 공존)하면 리스너 트랜잭션이
 * 롤백되므로, 격리 기록은 독립 트랜잭션으로 커밋해야 살아남는다.
 * 기록 실패는 삼키지 않는다 — 예외가 리스너로 전파되어 ack 없이 재시도되는 것이 무유실에 안전하다.
 */
@Component
public class JpaConsumedEventQuarantine implements ConsumedEventQuarantine {

    private final QuarantinedEventRepository quarantinedEventRepository;
    private final DuplicateEventRepository duplicateEventRepository;

    public JpaConsumedEventQuarantine(QuarantinedEventRepository quarantinedEventRepository,
                                      DuplicateEventRepository duplicateEventRepository) {
        this.quarantinedEventRepository = quarantinedEventRepository;
        this.duplicateEventRepository = duplicateEventRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(String consumerGroup, Cause cause, String causeDetail,
                           ConsumerRecord<String, String> record, UUID eventId) {
        // 같은 불량 레코드의 재전달(at-least-once)은 이미 격리돼 있다 — 좌표 유니크로 멱등
        if (quarantinedEventRepository.existsByConsumerGroupAndTopicAndKafkaPartitionAndKafkaOffset(
                consumerGroup, record.topic(), record.partition(), record.offset())) {
            return;
        }
        quarantinedEventRepository.save(new QuarantinedEventJpaEntity(
                consumerGroup, record.topic(), record.partition(), record.offset(),
                eventId, cause, causeDetail, record.value()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void duplicate(String consumerGroup, UUID eventId, ConsumerRecord<String, String> record) {
        DuplicateEventJpaEntity.DuplicateEventId id =
                new DuplicateEventJpaEntity.DuplicateEventId(consumerGroup, eventId);
        duplicateEventRepository.findById(id).ifPresentOrElse(
                existing -> {
                    existing.recordHit();
                    duplicateEventRepository.save(existing);
                },
                () -> duplicateEventRepository.save(new DuplicateEventJpaEntity(consumerGroup, eventId, record.topic())));
    }
}
