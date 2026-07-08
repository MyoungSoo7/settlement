package github.lms.lemuel.product.application.service;

import github.lms.lemuel.product.application.port.out.LoadCategoryPort;
import github.lms.lemuel.product.application.port.out.SaveCategoryPort;
import github.lms.lemuel.product.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock SaveCategoryPort saveCategoryPort;
    @Mock LoadCategoryPort loadCategoryPort;
    @InjectMocks CategoryService service;

    @Test @DisplayName("createCategory - parentId 가 null 이면 최상위 카테고리 생성")
    void create_root() {
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.createCategory("전자", "설명", null, 1);

        assertThat(result.isRootCategory()).isTrue();
        assertThat(result.getName()).isEqualTo("전자");
    }

    @Test @DisplayName("createCategory - parentId 가 있으면 하위 카테고리 생성")
    void create_sub() {
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.createCategory("노트북", "설명", 9L, 2);

        assertThat(result.isSubCategory()).isTrue();
        assertThat(result.getParentId()).isEqualTo(9L);
    }

    @Test @DisplayName("getCategoryById - 있으면 반환")
    void getById_found() {
        Category c = Category.create("A", null, 0);
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.of(c));
        assertThat(service.getCategoryById(1L)).isSameAs(c);
    }

    @Test @DisplayName("getCategoryById - 없으면 예외")
    void getById_notFound() {
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getCategoryById(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("조회 위임 - all/active/root/sub")
    void listDelegations() {
        when(loadCategoryPort.findAll()).thenReturn(List.of(Category.create("a", null, 0)));
        when(loadCategoryPort.findActiveCategories()).thenReturn(List.of());
        when(loadCategoryPort.findRootCategories()).thenReturn(List.of());
        when(loadCategoryPort.findSubCategories(3L)).thenReturn(List.of());

        assertThat(service.getAllCategories()).hasSize(1);
        assertThat(service.getActiveCategories()).isEmpty();
        assertThat(service.getRootCategories()).isEmpty();
        assertThat(service.getSubCategories(3L)).isEmpty();
    }

    @Test @DisplayName("updateCategory - 정보와 표시순서 갱신")
    void update_withDisplayOrder() {
        Category c = Category.create("old", "d", 0);
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.of(c));
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.updateCategory(1L, "new", "newDesc", 5);

        assertThat(result.getName()).isEqualTo("new");
        assertThat(result.getDisplayOrder()).isEqualTo(5);
    }

    @Test @DisplayName("updateCategory - displayOrder 가 null 이면 순서 유지")
    void update_nullDisplayOrder() {
        Category c = Category.create("old", "d", 4);
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.of(c));
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.updateCategory(1L, "new", null, null);

        assertThat(result.getDisplayOrder()).isEqualTo(4);
    }

    @Test @DisplayName("activateCategory - 활성화 후 저장")
    void activate() {
        Category c = Category.create("a", null, 0);
        c.deactivate();
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.of(c));
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activateCategory(1L);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(saveCategoryPort).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isTrue();
    }

    @Test @DisplayName("deactivateCategory - 비활성화 후 저장")
    void deactivate() {
        Category c = Category.create("a", null, 0);
        when(loadCategoryPort.findById(1L)).thenReturn(Optional.of(c));
        when(saveCategoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateCategory(1L);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(saveCategoryPort).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }
}
