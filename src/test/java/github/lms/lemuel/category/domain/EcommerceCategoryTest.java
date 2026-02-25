package github.lms.lemuel.category.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EcommerceCategory 도메인 테스트")
class EcommerceCategoryTest {

    @Nested
    @DisplayName("순환 참조 방지 테스트")
    class CircularReferenceTest {

        @Test
        @DisplayName("자기 자신을 부모로 지정할 수 없다")
        void cannotSetSelfAsParent() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);
            category.setId(1L);

            // When & Then
            assertThatThrownBy(() -> category.changeParent(1L, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be its own parent");
        }

        @Test
        @DisplayName("자기 자신을 부모로 하는 카테고리 생성 시 검증 실패")
        void validateParentIdPreventsCircularReference() {
            // Given
            EcommerceCategory category = new EcommerceCategory();
            category.setId(5L);
            category.setParentId(5L);

            // When & Then
            assertThatThrownBy(() -> category.validateParentId())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("circular reference");
        }
    }

    @Nested
    @DisplayName("최대 depth 초과 방지 테스트")
    class DepthLimitTest {

        @Test
        @DisplayName("depth 2를 초과하는 카테고리는 생성할 수 없다")
        void cannotCreateCategoryBeyondMaxDepth() {
            // Given: depth 2인 카테고리
            EcommerceCategory parentDepth2 = new EcommerceCategory();
            parentDepth2.setId(3L);
            parentDepth2.setDepth(2);
            parentDepth2.setSlug("electronics-computers-laptops");

            // When & Then: depth 3 생성 시도
            assertThatThrownBy(() ->
                    EcommerceCategory.createChild("게이밍 노트북", "gaming-laptops", 3L, 2, 1)
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("depth cannot exceed 2");
        }

        @Test
        @DisplayName("depth 0 (루트) 카테고리는 정상 생성된다")
        void canCreateRootCategory() {
            // When
            EcommerceCategory root = EcommerceCategory.createRoot("전자제품", "electronics", 1);

            // Then
            assertThat(root.getDepth()).isEqualTo(0);
            assertThat(root.isRoot()).isTrue();
            assertThat(root.canHaveChildren()).isTrue();
        }

        @Test
        @DisplayName("depth 1 카테고리는 정상 생성된다")
        void canCreateDepth1Category() {
            // When
            EcommerceCategory depth1 = EcommerceCategory.createChild("컴퓨터", "computers", 1L, 0, 1);

            // Then
            assertThat(depth1.getDepth()).isEqualTo(1);
            assertThat(depth1.isRoot()).isFalse();
            assertThat(depth1.canHaveChildren()).isTrue();
        }

        @Test
        @DisplayName("depth 2 카테고리는 정상 생성되며 더 이상 자식을 가질 수 없다")
        void canCreateDepth2CategoryButCannotHaveChildren() {
            // When
            EcommerceCategory depth2 = EcommerceCategory.createChild("노트북", "laptops", 2L, 1, 1);

            // Then
            assertThat(depth2.getDepth()).isEqualTo(2);
            assertThat(depth2.isRoot()).isFalse();
            assertThat(depth2.canHaveChildren()).isFalse();
        }

        @Test
        @DisplayName("depth 2 카테고리를 다른 depth 2의 부모로 이동 시도 시 실패")
        void cannotMoveDepth2CategoryUnderAnotherDepth2() {
            // Given
            EcommerceCategory category = new EcommerceCategory();
            category.setId(10L);
            category.setDepth(2);

            // When & Then
            assertThatThrownBy(() -> category.changeParent(11L, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceed maximum depth");
        }
    }

    @Nested
    @DisplayName("카테고리 생성 및 검증 테스트")
    class CategoryCreationTest {

        @Test
        @DisplayName("name이 빈 문자열이면 예외 발생")
        void nameCannotBeEmpty() {
            // When & Then
            assertThatThrownBy(() ->
                    EcommerceCategory.createRoot("", "electronics", 1)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name cannot be empty");
        }

        @Test
        @DisplayName("slug가 소문자, 숫자, 하이픈 이외의 문자를 포함하면 예외 발생")
        void slugMustContainOnlyValidCharacters() {
            // Given
            EcommerceCategory category = new EcommerceCategory();
            category.setName("전자제품");
            category.setSlug("Electronics!@#");

            // When & Then
            assertThatThrownBy(() -> category.validateSlug())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must contain only lowercase");
        }

        @Test
        @DisplayName("유효한 카테고리는 정상적으로 생성된다")
        void validCategoryCanBeCreated() {
            // When
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);

            // Then
            assertThat(category.getName()).isEqualTo("전자제품");
            assertThat(category.getSlug()).isEqualTo("electronics");
            assertThat(category.getDepth()).isEqualTo(0);
            assertThat(category.getSortOrder()).isEqualTo(1);
            assertThat(category.getIsActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("카테고리 비즈니스 로직 테스트")
    class BusinessLogicTest {

        @Test
        @DisplayName("카테고리를 활성화할 수 있다")
        void canActivateCategory() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);
            category.deactivate();

            // When
            category.activate();

            // Then
            assertThat(category.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("삭제된 카테고리는 활성화할 수 없다")
        void cannotActivateDeletedCategory() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);
            category.softDelete();

            // When & Then
            assertThatThrownBy(() -> category.activate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot activate deleted category");
        }

        @Test
        @DisplayName("카테고리를 soft delete 처리할 수 있다")
        void canSoftDeleteCategory() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);

            // When
            category.softDelete();

            // Then
            assertThat(category.isDeleted()).isTrue();
            assertThat(category.getIsActive()).isFalse();
            assertThat(category.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("정렬 순서를 변경할 수 있다")
        void canChangeSortOrder() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);

            // When
            category.changeSortOrder(10);

            // Then
            assertThat(category.getSortOrder()).isEqualTo(10);
        }

        @Test
        @DisplayName("음수 정렬 순서는 허용되지 않는다")
        void sortOrderCannotBeNegative() {
            // Given
            EcommerceCategory category = EcommerceCategory.createRoot("전자제품", "electronics", 1);

            // When & Then
            assertThatThrownBy(() -> category.changeSortOrder(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be zero or greater");
        }
    }
}
