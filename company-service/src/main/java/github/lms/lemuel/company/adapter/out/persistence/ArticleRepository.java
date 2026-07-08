package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.ArticleSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ArticleRepository extends JpaRepository<ArticleJpaEntity, Long> {

    Page<ArticleJpaEntity> findByStockCode(String stockCode, Pageable pageable);

    Page<ArticleJpaEntity> findByStockCodeAndSource(String stockCode, ArticleSource source, Pageable pageable);

    boolean existsByUrlHash(String urlHash);

    // 발행일시가 since 이후이거나, 발행일시 미상(null)이면 수집일시 기준으로 포함 — 평판 산정 윈도우.
    @Query("""
            SELECT a FROM ArticleJpaEntity a
            WHERE a.stockCode = :stockCode
              AND (a.publishedAt >= :since OR (a.publishedAt IS NULL AND a.collectedAt >= :since))
            """)
    List<ArticleJpaEntity> findForScoring(@Param("stockCode") String stockCode, @Param("since") Instant since);
}
