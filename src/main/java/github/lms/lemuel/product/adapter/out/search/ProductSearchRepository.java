package github.lms.lemuel.product.adapter.out.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductSearchDocument, String> {
    List<ProductSearchDocument> findByNameContaining(String name);
    List<ProductSearchDocument> findByStatus(String status);
    List<ProductSearchDocument> findByCategoryId(Long categoryId);
}
