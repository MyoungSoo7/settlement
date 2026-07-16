package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArticleSentimentRepository
        extends JpaRepository<ArticleSentimentJpaEntity, ArticleSentimentJpaEntity.Key> {

    Optional<ArticleSentimentJpaEntity> findByUrlHashAndProvider(String urlHash, String provider);
}
