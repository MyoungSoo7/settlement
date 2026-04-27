package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SpringDataProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    Optional<ProductJpaEntity> findByName(String name);

    boolean existsByName(String name);

    List<ProductJpaEntity> findByStatus(ProductStatus status);

    @Query("SELECT p FROM ProductJpaEntity p WHERE p.status = 'ACTIVE' AND p.stockQuantity > 0")
    List<ProductJpaEntity> findAvailableProducts();
}
