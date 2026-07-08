package github.lms.lemuel.commondata.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataRecordRepository extends JpaRepository<DataRecordJpaEntity, Long> {

    Optional<DataRecordJpaEntity> findBySourceIdAndRecordKey(Long sourceId, String recordKey);

    List<DataRecordJpaEntity> findBySourceIdOrderByCollectedAtDescIdDesc(Long sourceId, Limit limit);
}
