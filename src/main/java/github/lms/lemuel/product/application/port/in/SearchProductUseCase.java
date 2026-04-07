package github.lms.lemuel.product.application.port.in;

import github.lms.lemuel.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface SearchProductUseCase {
    Page<Product> search(String keyword, Pageable pageable);
    Page<Product> searchWithFilters(SearchCommand command);
    List<String> suggest(String prefix, int size);
    void indexProduct(Long productId);
    void reindexAll();

    record SearchCommand(
        String keyword,
        Long categoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String status,
        int page,
        int size
    ) {}
}
