package github.lms.lemuel.insurance.adapter.in.kafka.quarantine;

import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 보험 컨슈머의 격리 기록을 리스너와 독립된 트랜잭션으로 보존한다. */
@Component
public class JpaConsumedEventQuarantine implements ConsumedEventQuarantine {

    private final QuarantinedEventRepository repository;

    public JpaConsumedEventQuarantine(QuarantinedEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(String consumerGroup, Cause cause, String causeDetail,
                           ConsumerRecord<String, String> record, UUID eventId) {
        if (repository.existsByConsumerGroupAndTopicAndKafkaPartitionAndKafkaOffset(
                consumerGroup, record.topic(), record.partition(), record.offset())) {
            return;
        }
        repository.save(new QuarantinedEventJpaEntity(
                consumerGroup,
                record.topic(),
                record.partition(),
                record.offset(),
                eventId,
                cause,
                causeDetail,
                record.value()));
    }
}
