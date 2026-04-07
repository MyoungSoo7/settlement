package github.lms.lemuel.product.adapter.out.search;

import github.lms.lemuel.product.application.port.out.SearchProductPort;
import github.lms.lemuel.product.domain.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * No-Op Product Search Adapter
 * 검색 기능이 비활성화된 경우 사용되는 구현체
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpProductSearchAdapter implements SearchProductPort {

    @Override
    public void index(Product product) {
        log.debug("Search is disabled, skipping product indexing: productId={}", product.getId());
    }

    @Override
    public void delete(String productId) {
        log.debug("Search is disabled, skipping product index deletion: productId={}", productId);
    }

    @Override
    public Page<Product> search(String keyword, Pageable pageable) {
        log.debug("Search is disabled, returning empty results for keyword: {}", keyword);
        return new PageImpl<>(Collections.emptyList(), pageable, 0);
    }

    @Override
    public Page<Product> searchWithFilters(String keyword, Long categoryId,
                                           BigDecimal minPrice, BigDecimal maxPrice,
                                           String status, Pageable pageable) {
        log.debug("Search is disabled, returning empty results for filtered search");
        return new PageImpl<>(Collections.emptyList(), pageable, 0);
    }

    @Override
    public List<String> suggest(String prefix, int size) {
        log.debug("Search is disabled, returning empty suggestions for prefix: {}", prefix);
        return Collections.emptyList();
    }
}
