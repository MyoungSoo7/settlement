package github.lms.lemuel.insurance.adapter.in.kafka.quarantine;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuarantinedEventRepository extends JpaRepository<QuarantinedEventJpaEntity, Long> {

    boolean existsByConsumerGroupAndTopicAndKafkaPartitionAndKafkaOffset(
            String consumerGroup, String topic, int kafkaPartition, long kafkaOffset);
}
