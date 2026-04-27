package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataCategoryRepository extends JpaRepository<CategoryJpaEntity, Long> {

    Optional<CategoryJpaEntity> findByName(String name);

    List<CategoryJpaEntity> findByIsActiveTrue();

    List<CategoryJpaEntity> findByParentIdIsNull();

    List<CategoryJpaEntity> findByParentId(Long parentId);

    List<CategoryJpaEntity> findAllByOrderByDisplayOrderAsc();

    List<CategoryJpaEntity> findByIsActiveTrueOrderByDisplayOrderAsc();
}
