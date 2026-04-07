package github.lms.lemuel.product.adapter.in.web;

import github.lms.lemuel.product.adapter.in.web.response.ProductSearchResponse;
import github.lms.lemuel.product.application.port.in.SearchProductUseCase;
import github.lms.lemuel.product.application.port.in.SearchProductUseCase.SearchCommand;
import github.lms.lemuel.product.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products/search")
@RequiredArgsConstructor
public class ProductSearchController {

    private final SearchProductUseCase searchProductUseCase;

    @GetMapping
    public ResponseEntity<ProductSearchResponse.PageResponse> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Product> result = searchProductUseCase.search(keyword, PageRequest.of(page, size));
        return ResponseEntity.ok(ProductSearchResponse.PageResponse.from(result));
    }

    @GetMapping("/filter")
    public ResponseEntity<ProductSearchResponse.PageResponse> searchWithFilters(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SearchCommand command = new SearchCommand(keyword, categoryId, minPrice, maxPrice, status, page, size);
        Page<Product> result = searchProductUseCase.searchWithFilters(command);
        return ResponseEntity.ok(ProductSearchResponse.PageResponse.from(result));
    }

    @GetMapping("/suggest")
    public ResponseEntity<List<String>> suggest(
            @RequestParam String prefix,
            @RequestParam(defaultValue = "5") int size) {
        List<String> suggestions = searchProductUseCase.suggest(prefix, size);
        return ResponseEntity.ok(suggestions);
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, String>> reindexAll() {
        searchProductUseCase.reindexAll();
        return ResponseEntity.ok(Map.of("message", "Reindex completed"));
    }

    @PostMapping("/{productId}/index")
    public ResponseEntity<Map<String, String>> indexProduct(@PathVariable Long productId) {
        searchProductUseCase.indexProduct(productId);
        return ResponseEntity.ok(Map.of("message", "Product indexed: " + productId));
    }
}
