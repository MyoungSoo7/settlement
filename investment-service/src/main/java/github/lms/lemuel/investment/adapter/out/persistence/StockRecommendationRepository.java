package github.lms.lemuel.investment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockRecommendationRepository extends JpaRepository<StockRecommendationJpaEntity, Long> {

    @Query("select max(r.recommendedDate) from StockRecommendationJpaEntity r")
    Optional<LocalDate> findLatestRecommendedDate();

    List<StockRecommendationJpaEntity> findByRecommendedDateOrderByDisplayOrderAsc(LocalDate recommendedDate);
}
