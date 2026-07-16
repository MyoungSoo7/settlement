package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.SentimentCachePort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 기사 감성 캐시 영속 어댑터. (url_hash, provider) 로 조회·저장하며, 동시 저장 충돌(PK 중복)은
 * 멱등하게 무시한다(먼저 저장한 값 존중). persistence 어댑터라 단위 커버리지 게이트 제외.
 */
@Component
public class SentimentCachePersistenceAdapter implements SentimentCachePort {

    private static final Logger log = LoggerFactory.getLogger(SentimentCachePersistenceAdapter.class);

    private final ArticleSentimentRepository repository;

    public SentimentCachePersistenceAdapter(ArticleSentimentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArticleSentiment> find(String urlHash, String provider) {
        return repository.findByUrlHashAndProvider(urlHash, provider)
                .map(ArticleSentimentJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(String urlHash, String provider, ArticleSentiment sentiment) {
        try {
            repository.save(ArticleSentimentJpaEntity.of(urlHash, provider, sentiment, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // (url_hash, provider) PK 충돌 — 동시 재계산 레이스. 먼저 저장한 캐시를 존중.
            log.debug("감성 캐시 동시 저장 충돌 스킵 urlHash={} provider={}", urlHash, provider);
        }
    }
}
