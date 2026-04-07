package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataProductOptionValueRepository extends JpaRepository<ProductOptionValueJpaEntity, Long> {

    List<ProductOptionValueJpaEntity> findByOptionIdOrderBySortOrder(Long optionId);

    void deleteByOptionId(Long optionId);
}
