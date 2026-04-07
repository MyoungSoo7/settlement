package github.lms.lemuel.product.application.port.out;

import github.lms.lemuel.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface SearchProductPort {
    void index(Product product);
    void delete(String productId);
    Page<Product> search(String keyword, Pageable pageable);
    Page<Product> searchWithFilters(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, String status, Pageable pageable);
    List<String> suggest(String prefix, int size);
}
