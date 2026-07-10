package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryMapperTest {

    private final CategoryMapper mapper = new CategoryMapper();

    @Test
    @DisplayName("toJpaEntity: null 이면 null")
    void toJpaEntity_null() {
        assertThat(mapper.toJpaEntity(null)).isNull();
    }

    @Test
    @DisplayName("toDomainEntity: null 이면 null")
    void toDomainEntity_null() {
        assertThat(mapper.toDomainEntity(null)).isNull();
    }

    @Test
    @DisplayName("도메인 -> 엔티티 -> 도메인 왕복 시 전 필드 보존")
    void roundTrip() {
        LocalDateTime now = LocalDateTime.now();
        Category category = new Category(1L, "의류", "설명", 5L, 2, true, now, now);

        CategoryJpaEntity entity = mapper.toJpaEntity(category);
        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getName()).isEqualTo("의류");
        assertThat(entity.getDescription()).isEqualTo("설명");
        assertThat(entity.getParentId()).isEqualTo(5L);
        assertThat(entity.getDisplayOrder()).isEqualTo(2);
        assertThat(entity.getIsActive()).isTrue();

        Category restored = mapper.toDomainEntity(entity);
        assertThat(restored.getId()).isEqualTo(1L);
        assertThat(restored.getName()).isEqualTo("의류");
        assertThat(restored.getDescription()).isEqualTo("설명");
        assertThat(restored.getParentId()).isEqualTo(5L);
        assertThat(restored.getDisplayOrder()).isEqualTo(2);
        assertThat(restored.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("루트 카테고리(parentId null) 도 보존")
    void roundTrip_rootCategory() {
        LocalDateTime now = LocalDateTime.now();
        Category category = new Category(2L, "루트", null, null, 0, false, now, now);

        CategoryJpaEntity entity = mapper.toJpaEntity(category);
        assertThat(entity.getParentId()).isNull();

        Category restored = mapper.toDomainEntity(entity);
        assertThat(restored.getParentId()).isNull();
        assertThat(restored.getIsActive()).isFalse();
        assertThat(restored.isRootCategory()).isTrue();
    }
}
