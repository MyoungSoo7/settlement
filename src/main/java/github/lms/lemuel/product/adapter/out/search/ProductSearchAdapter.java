package github.lms.lemuel.product.adapter.out.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import github.lms.lemuel.product.application.port.out.SearchProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.ProductStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Product 검색 Adapter (Outbound Adapter)
 * SearchProductPort 구현 - Elasticsearch 연동
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = false)
public class ProductSearchAdapter implements SearchProductPort {

    private final ProductSearchRepository productSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void index(Product product) {
        try {
            ProductSearchDocument document = toDocument(product);
            elasticsearchOperations.save(document);
            log.info("Product indexed successfully: productId={}", product.getId());
        } catch (Exception e) {
            log.error("Failed to index product: productId={}", product.getId(), e);
        }
    }

    @Override
    public void delete(String productId) {
        try {
            elasticsearchOperations.delete(productId, ProductSearchDocument.class);
            log.info("Product index deleted: productId={}", productId);
        } catch (Exception e) {
            log.error("Failed to delete product index: productId={}", productId, e);
        }
    }

    @Override
    public Page<Product> search(String keyword, Pageable pageable) {
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.multiMatch(mm -> mm
                            .query(keyword)
                            .fields("name^2", "description")
                    ))
                    .withPageable(pageable)
                    .build();

            SearchHits<ProductSearchDocument> searchHits =
                    elasticsearchOperations.search(query, ProductSearchDocument.class);

            List<Product> products = searchHits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toDomain)
                    .collect(Collectors.toList());

            return new PageImpl<>(products, pageable, searchHits.getTotalHits());
        } catch (Exception e) {
            log.error("Failed to search products with keyword: {}", keyword, e);
            return Page.empty(pageable);
        }
    }

    @Override
    public Page<Product> searchWithFilters(String keyword, Long categoryId,
                                           BigDecimal minPrice, BigDecimal maxPrice,
                                           String status, Pageable pageable) {
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(buildFilterQuery(keyword, categoryId, minPrice, maxPrice, status)))
                    .withPageable(pageable)
                    .build();

            SearchHits<ProductSearchDocument> searchHits =
                    elasticsearchOperations.search(query, ProductSearchDocument.class);

            List<Product> products = searchHits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toDomain)
                    .collect(Collectors.toList());

            return new PageImpl<>(products, pageable, searchHits.getTotalHits());
        } catch (Exception e) {
            log.error("Failed to search products with filters", e);
            return Page.empty(pageable);
        }
    }

    @Override
    public List<String> suggest(String prefix, int size) {
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.prefix(p -> p
                            .field("name")
                            .value(prefix.toLowerCase())
                    ))
                    .withMaxResults(size)
                    .build();

            SearchHits<ProductSearchDocument> searchHits =
                    elasticsearchOperations.search(query, ProductSearchDocument.class);

            return searchHits.getSearchHits().stream()
                    .map(hit -> hit.getContent().getName())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get suggestions for prefix: {}", prefix, e);
            return Collections.emptyList();
        }
    }

    private BoolQuery buildFilterQuery(String keyword, Long categoryId,
                                       BigDecimal minPrice, BigDecimal maxPrice,
                                       String status) {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // Keyword search on name and description
        if (keyword != null && !keyword.isBlank()) {
            boolBuilder.must(Query.of(q -> q.multiMatch(mm -> mm
                    .query(keyword)
                    .fields("name^2", "description")
            )));
        }

        // Category filter
        if (categoryId != null) {
            boolBuilder.filter(Query.of(q -> q.term(t -> t
                    .field("categoryId")
                    .value(categoryId)
            )));
        }

        // Price range filter
        if (minPrice != null || maxPrice != null) {
            boolBuilder.filter(Query.of(q -> q.range(r -> r
                    .number(n -> {
                        n.field("price");
                        if (minPrice != null) {
                            n.gte(minPrice.doubleValue());
                        }
                        if (maxPrice != null) {
                            n.lte(maxPrice.doubleValue());
                        }
                        return n;
                    })
            )));
        }

        // Status filter
        if (status != null && !status.isBlank()) {
            boolBuilder.filter(Query.of(q -> q.term(t -> t
                    .field("status")
                    .value(status)
            )));
        }

        return boolBuilder.build();
    }

    private ProductSearchDocument toDocument(Product product) {
        return ProductSearchDocument.builder()
                .id(product.getId().toString())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .status(product.getStatus() != null ? product.getStatus().name() : null)
                .categoryId(product.getCategoryId())
                .tags(Collections.emptyList())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private Product toDomain(ProductSearchDocument document) {
        Product product = new Product();
        if (document.getId() != null) {
            product.setId(Long.parseLong(document.getId()));
        }
        product.setName(document.getName());
        product.setDescription(document.getDescription());
        product.setPrice(document.getPrice());
        product.setStockQuantity(document.getStockQuantity());
        product.setStatus(document.getStatus() != null
                ? ProductStatus.fromString(document.getStatus())
                : ProductStatus.ACTIVE);
        product.setCategoryId(document.getCategoryId());
        product.setCreatedAt(document.getCreatedAt());
        product.setUpdatedAt(document.getUpdatedAt());
        return product;
    }
}
