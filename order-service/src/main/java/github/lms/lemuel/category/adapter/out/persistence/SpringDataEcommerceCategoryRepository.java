package github.lms.lemuel.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataEcommerceCategoryRepository extends JpaRepository<EcommerceCategoryJpaEntity, Long> {

    Optional<EcommerceCategoryJpaEntity> findBySlug(String slug);

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findAllNotDeleted();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.deletedAt IS NULL AND c.isActive = true ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findAllActiveNotDeleted();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.parentId IS NULL AND c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findRootCategories();

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL ORDER BY c.sortOrder ASC")
    List<EcommerceCategoryJpaEntity> findByParentId(@Param("parentId") Long parentId);

    @Query("SELECT c FROM EcommerceCategoryJpaEntity c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<EcommerceCategoryJpaEntity> findByIdNotDeleted(@Param("id") Long id);

    @Query("SELECT COUNT(c) FROM EcommerceCategoryJpaEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL")
    long countChildrenByParentId(@Param("parentId") Long parentId);

    @Query("SELECT CASE WHEN COUNT(pc) > 0 THEN true ELSE false END " +
           "FROM ProductEcommerceCategoryJpaEntity pc WHERE pc.categoryId = :categoryId")
    boolean hasProducts(@Param("categoryId") Long categoryId);
}
