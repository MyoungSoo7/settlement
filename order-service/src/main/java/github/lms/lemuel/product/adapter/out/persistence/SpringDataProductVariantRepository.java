package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataProductVariantRepository extends JpaRepository<ProductVariantJpaEntity, Long> {

    Optional<ProductVariantJpaEntity> findBySku(String sku);

    List<ProductVariantJpaEntity> findByProductId(Long productId);
}
