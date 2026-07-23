package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuarantinedEventRepository extends JpaRepository<QuarantinedEventJpaEntity, Long> {

    /** 재처리 동시 호출(더블클릭 등) 직렬화 — 같은 행의 replay 경쟁을 행 잠금으로 차단한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuarantinedEventJpaEntity q where q.id = :id")
    Optional<QuarantinedEventJpaEntity> findByIdForUpdate(@Param("id") long id);

    boolean existsByConsumerGroupAndTopicAndKafkaPartitionAndKafkaOffset(
            String consumerGroup, String topic, int kafkaPartition, long kafkaOffset);

    List<QuarantinedEventJpaEntity> findTop100ByStatusOrderByOccurredAtDesc(QuarantinedEventJpaEntity.Status status);

    long countByStatus(QuarantinedEventJpaEntity.Status status);
}
