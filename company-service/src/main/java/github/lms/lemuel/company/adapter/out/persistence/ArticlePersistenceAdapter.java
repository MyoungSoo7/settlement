package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.SaveArticlePort;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ArticlePersistenceAdapter implements LoadArticlePort, SaveArticlePort {

    private static final Logger log = LoggerFactory.getLogger(ArticlePersistenceAdapter.class);
    private static final Sort LATEST_FIRST = Sort.by(
            Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));

    private final ArticleRepository repository;

    public ArticlePersistenceAdapter(ArticleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult findByCompany(String stockCode, ArticleSource source, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, LATEST_FIRST);
        Page<ArticleJpaEntity> result = source == null
                ? repository.findByStockCode(stockCode, pageable)
                : repository.findByStockCodeAndSource(stockCode, source, pageable);
        return new PageResult(result.map(ArticleJpaEntity::toDomain).getContent(), result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> findForScoring(String stockCode, java.time.Instant since) {
        return repository.findForScoring(stockCode, since).stream()
                .map(ArticleJpaEntity::toDomain)
                .toList();
    }

    /**
     * 항목별 개별 tx 로 저장 — 배치 중간에 중복(UNIQUE 충돌)이 있어도 나머지가 롤백되지 않는다.
     * exists 선체크가 1차, UNIQUE 제약 충돌 catch 가 2차(동시 수집 레이스) 방어.
     */
    @Override
    public int saveNew(List<Article> articles) {
        Set<String> seenInBatch = new HashSet<>();
        int saved = 0;
        for (Article article : articles) {
            if (!seenInBatch.add(article.urlHash()) || repository.existsByUrlHash(article.urlHash())) {
                continue;
            }
            try {
                repository.save(ArticleJpaEntity.fromDomain(article));
                saved++;
            } catch (DataIntegrityViolationException e) {
                log.debug("동시 수집 중복 스킵 urlHash={}", article.urlHash());
            }
        }
        return saved;
    }
}
