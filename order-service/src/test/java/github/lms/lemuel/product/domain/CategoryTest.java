package github.lms.lemuel.product.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CategoryTest {

    @Test @DisplayName("기본 생성자: 기본값이 올바르게 설정된다")
    void defaultConstructor() {
        Category cat = new Category();
        assertThat(cat.getDisplayOrder()).isZero();
        assertThat(cat.getIsActive()).isTrue();
        assertThat(cat.getCreatedAt()).isNotNull();
        assertThat(cat.getUpdatedAt()).isNotNull();
    }

    @Test @DisplayName("전체 생성자: 모든 값이 올바르게 설정된다")
    void fullConstructor_allValues() {
        var created = java.time.LocalDateTime.of(2025, 1, 1, 0, 0);
        var updated = java.time.LocalDateTime.of(2025, 6, 1, 0, 0);
        Category cat = new Category(1L, "전자제품", "설명", 2L, 5, false, created, updated);
        assertThat(cat.getId()).isEqualTo(1L);
        assertThat(cat.getName()).isEqualTo("전자제품");
        assertThat(cat.getParentId()).isEqualTo(2L);
        assertThat(cat.getDisplayOrder()).isEqualTo(5);
        assertThat(cat.getIsActive()).isFalse();
    }

    @Test @DisplayName("전체 생성자: null 값들에 기본값이 적용된다")
    void fullConstructor_nullDefaults() {
        Category cat = new Category(1L, "카테고리", null, null, null, null, null, null);
        assertThat(cat.getDisplayOrder()).isZero();
        assertThat(cat.getIsActive()).isTrue();
        assertThat(cat.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create: 최상위 카테고리를 생성한다")
    void create_rootCategory() {
        Category cat = Category.create("의류", "의류 카테고리", 3);
        assertThat(cat.getName()).isEqualTo("의류");
        assertThat(cat.getDescription()).isEqualTo("의류 카테고리");
        assertThat(cat.getDisplayOrder()).isEqualTo(3);
        assertThat(cat.getParentId()).isNull();
    }

    @Test @DisplayName("create: null 이름이면 예외")
    void create_nullName() {
        assertThatThrownBy(() -> Category.create(null, "설명", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name cannot be empty");
    }

    @Test @DisplayName("create: 빈 이름이면 예외")
    void create_emptyName() {
        assertThatThrownBy(() -> Category.create("  ", "설명", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("create: 100자 초과 이름이면 예외")
    void create_longName() {
        assertThatThrownBy(() -> Category.create("A".repeat(101), "설명", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name must not exceed 100 characters");
    }

    @Test @DisplayName("createSubCategory: 하위 카테고리를 생성한다")
    void createSubCategory() {
        Category cat = Category.createSubCategory("셔츠", "셔츠 카테고리", 10L, 2);
        assertThat(cat.getName()).isEqualTo("셔츠");
        assertThat(cat.getParentId()).isEqualTo(10L);
    }

    @Test @DisplayName("deactivate: 비활성화")
    void deactivate() {
        Category cat = Category.create("카테고리", "설명", 0);
        cat.deactivate();
        assertThat(cat.getIsActive()).isFalse();
    }

    @Test @DisplayName("activate: 활성화")
    void activate() {
        Category cat = Category.create("카테고리", "설명", 0);
        cat.deactivate();
        cat.activate();
        assertThat(cat.getIsActive()).isTrue();
    }

    @Test @DisplayName("updateInfo: 이름과 설명을 업데이트한다")
    void updateInfo_both() {
        Category cat = Category.create("원본", "원본 설명", 0);
        cat.updateInfo("수정됨", "새 설명");
        assertThat(cat.getName()).isEqualTo("수정됨");
        assertThat(cat.getDescription()).isEqualTo("새 설명");
    }

    @Test @DisplayName("updateInfo: null 이름이면 기존 유지")
    void updateInfo_nullName() {
        Category cat = Category.create("원본", "설명", 0);
        cat.updateInfo(null, "새 설명");
        assertThat(cat.getName()).isEqualTo("원본");
    }

    @Test @DisplayName("updateInfo: null 설명이면 기존 유지")
    void updateInfo_nullDescription() {
        Category cat = Category.create("원본", "설명", 0);
        cat.updateInfo("새이름", null);
        assertThat(cat.getDescription()).isEqualTo("설명");
    }

    @Test @DisplayName("changeDisplayOrder: 순서 변경")
    void changeDisplayOrder_valid() {
        Category cat = Category.create("카테고리", "설명", 0);
        cat.changeDisplayOrder(5);
        assertThat(cat.getDisplayOrder()).isEqualTo(5);
    }

    @Test @DisplayName("changeDisplayOrder: null이면 예외")
    void changeDisplayOrder_null() {
        Category cat = Category.create("카테고리", "설명", 0);
        assertThatThrownBy(() -> cat.changeDisplayOrder(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("changeDisplayOrder: 음수이면 예외")
    void changeDisplayOrder_negative() {
        Category cat = Category.create("카테고리", "설명", 0);
        assertThatThrownBy(() -> cat.changeDisplayOrder(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("changeParent: 부모 변경")
    void changeParent_valid() {
        Category cat = Category.create("카테고리", "설명", 0);
        cat.changeParent(10L);
        assertThat(cat.getParentId()).isEqualTo(10L);
    }

    @Test @DisplayName("changeParent: null이면 최상위")
    void changeParent_null() {
        Category cat = Category.createSubCategory("하위", "설명", 5L, 0);
        cat.changeParent(null);
        assertThat(cat.isRootCategory()).isTrue();
    }

    @Test @DisplayName("changeParent: 자기 자신이면 예외")
    void changeParent_self() {
        Category cat = Category.create("카테고리", "설명", 0);
        cat.setId(7L);
        assertThatThrownBy(() -> cat.changeParent(7L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("isRootCategory / isSubCategory")
    void rootAndSub() {
        Category root = Category.create("최상위", "설명", 0);
        assertThat(root.isRootCategory()).isTrue();
        assertThat(root.isSubCategory()).isFalse();

        Category sub = Category.createSubCategory("하위", "설명", 1L, 0);
        assertThat(sub.isSubCategory()).isTrue();
        assertThat(sub.isRootCategory()).isFalse();
    }
}
