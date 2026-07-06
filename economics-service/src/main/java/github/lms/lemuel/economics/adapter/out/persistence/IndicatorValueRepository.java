package github.lms.lemuel.economics.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndicatorValueRepository extends JpaRepository<IndicatorValueJpaEntity, Long> {

    List<IndicatorValueJpaEntity> findByIndicatorCodeOrderByObservedDateDesc(String indicatorCode, Limit limit);

    List<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDateBetweenOrderByObservedDateAsc(
            String indicatorCode, LocalDate from, LocalDate to);

    Optional<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDate(String indicatorCode, LocalDate observedDate);
}
