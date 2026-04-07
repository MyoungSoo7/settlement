package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.in.SearchProductUseCase;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.application.port.out.SearchProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService implements SearchProductUseCase {

    private final SearchProductPort searchProductPort;
    private final LoadProductPort loadProductPort;

    @Override
    public Page<Product> search(String keyword, Pageable pageable) {
        return searchProductPort.search(keyword, pageable);
    }

    @Override
    public Page<Product> searchWithFilters(SearchCommand command) {
        Pageable pageable = PageRequest.of(command.page(), command.size());
        return searchProductPort.searchWithFilters(
                command.keyword(),
                command.categoryId(),
                command.minPrice(),
                command.maxPrice(),
                command.status(),
                pageable
        );
    }

    @Override
    public List<String> suggest(String prefix, int size) {
        return searchProductPort.suggest(prefix, size);
    }

    @Override
    public void indexProduct(Long productId) {
        Product product = loadProductPort.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + productId));
        searchProductPort.index(product);
        log.info("Product indexed: productId={}", productId);
    }

    @Override
    public void reindexAll() {
        List<Product> allProducts = loadProductPort.findAll();
        log.info("Reindexing all products: count={}", allProducts.size());
        for (Product product : allProducts) {
            try {
                searchProductPort.index(product);
            } catch (Exception e) {
                log.error("Failed to reindex product: productId={}", product.getId(), e);
            }
        }
        log.info("Reindex completed: count={}", allProducts.size());
    }
}
