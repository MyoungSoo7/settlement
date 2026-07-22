package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DuplicateEventRepository extends JpaRepository<DuplicateEventJpaEntity, DuplicateEventJpaEntity.DuplicateEventId> {

    @Query("select coalesce(sum(d.hitCount), 0) from DuplicateEventJpaEntity d")
    long totalHits();
}
