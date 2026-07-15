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

    /** 해당 추천일의 기존 세트를 삭제한다(재스크리닝 시 교체용). 삭제 행 수 반환. */
    long deleteByRecommendedDate(LocalDate recommendedDate);
}
