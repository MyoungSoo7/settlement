package github.lms.lemuel.economics.adapter.out.persistence;

import github.lms.lemuel.economics.domain.ValueSource;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndicatorValueRepository extends JpaRepository<IndicatorValueJpaEntity, Long> {

    List<IndicatorValueJpaEntity> findByIndicatorCodeOrderByObservedDateDesc(String indicatorCode, Limit limit);

    List<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDateBetweenOrderByObservedDateAsc(
            String indicatorCode, LocalDate from, LocalDate to);

    Optional<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDate(String indicatorCode, LocalDate observedDate);

    /** 지정 지표에서 {@code date} 이후의 특정 source 행을 벌크 삭제한다(후행 SEED 제거용). */
    @Modifying(clearAutomatically = true)
    @Query("delete from IndicatorValueJpaEntity v "
            + "where v.indicatorCode = :code and v.source = :source and v.observedDate > :date")
    int deleteByIndicatorCodeAndSourceNewerThan(@Param("code") String code,
                                                @Param("source") ValueSource source,
                                                @Param("date") LocalDate date);
}
