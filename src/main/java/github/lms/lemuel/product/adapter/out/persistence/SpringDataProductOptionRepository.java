package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataProductOptionRepository extends JpaRepository<ProductOptionJpaEntity, Long> {

    List<ProductOptionJpaEntity> findByProductIdOrderBySortOrder(Long productId);
}
