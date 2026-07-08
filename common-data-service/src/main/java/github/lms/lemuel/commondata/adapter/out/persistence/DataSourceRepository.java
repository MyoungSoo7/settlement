package github.lms.lemuel.commondata.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSourceJpaEntity, Long> {

    Optional<DataSourceJpaEntity> findByCode(String code);

    List<DataSourceJpaEntity> findAllByOrderByCodeAsc();
}
