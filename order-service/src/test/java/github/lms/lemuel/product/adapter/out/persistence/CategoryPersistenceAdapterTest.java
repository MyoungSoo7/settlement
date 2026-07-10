package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryPersistenceAdapterTest {

    @Mock SpringDataCategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper = new CategoryMapper();

    private CategoryPersistenceAdapter adapter() {
        return new CategoryPersistenceAdapter(categoryRepository, categoryMapper);
    }

    private CategoryJpaEntity entity(Long id, String name, Long parentId) {
        return new CategoryJpaEntity(id, name, "설명", parentId, 1, true,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("save: 매핑 후 저장하고 도메인 복원")
    void save() {
        Category category = Category.create("의류", "설명", 1);
        when(categoryRepository.save(any(CategoryJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Category saved = adapter().save(category);

        assertThat(saved.getName()).isEqualTo("의류");
    }

    @Test
    @DisplayName("findById: 존재하면 매핑")
    void findById_found() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(entity(1L, "의류", null)));

        Optional<Category> result = adapter().findById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("의류");
    }

    @Test
    @DisplayName("findById: 미존재면 empty")
    void findById_notFound() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(adapter().findById(1L)).isEmpty();
    }

    @Test
    @DisplayName("findByName: 위임")
    void findByName() {
        when(categoryRepository.findByName("의류")).thenReturn(Optional.of(entity(1L, "의류", null)));

        assertThat(adapter().findByName("의류")).isPresent();
    }

    @Test
    @DisplayName("findAll: displayOrder 순 조회 위임")
    void findAll() {
        when(categoryRepository.findAllByOrderByDisplayOrderAsc())
                .thenReturn(List.of(entity(1L, "의류", null), entity(2L, "잡화", null)));

        List<Category> result = adapter().findAll();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findActiveCategories: 활성 카테고리만 조회 위임")
    void findActiveCategories() {
        when(categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(entity(1L, "의류", null)));

        assertThat(adapter().findActiveCategories()).hasSize(1);
    }

    @Test
    @DisplayName("findRootCategories: 부모 없는 카테고리 조회 위임")
    void findRootCategories() {
        when(categoryRepository.findByParentIdIsNull())
                .thenReturn(List.of(entity(1L, "의류", null)));

        assertThat(adapter().findRootCategories()).hasSize(1);
    }

    @Test
    @DisplayName("findSubCategories: 부모 ID로 조회 위임")
    void findSubCategories() {
        when(categoryRepository.findByParentId(1L))
                .thenReturn(List.of(entity(2L, "상의", 1L)));

        List<Category> result = adapter().findSubCategories(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getParentId()).isEqualTo(1L);
    }
}
