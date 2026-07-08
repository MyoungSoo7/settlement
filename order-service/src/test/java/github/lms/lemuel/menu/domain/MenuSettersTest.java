package github.lms.lemuel.menu.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Menu — 전체 세터 왕복 커버리지")
class MenuSettersTest {

    @Test
    @DisplayName("모든 세터/게터 왕복")
    void allSetters() {
        Menu menu = new Menu();
        LocalDateTime t = LocalDateTime.now();
        menu.setId(1L);
        menu.setParentId(2L);
        menu.setName("메뉴");
        menu.setPath("/path");
        menu.setIcon("icon");
        menu.setSortOrder(9);
        menu.setRequiredRole("ADMIN");
        menu.setVisible(false);
        menu.setActive(false);
        menu.setCreatedAt(t);
        menu.setUpdatedAt(t);

        assertThat(menu.getId()).isEqualTo(1L);
        assertThat(menu.getParentId()).isEqualTo(2L);
        assertThat(menu.getName()).isEqualTo("메뉴");
        assertThat(menu.getPath()).isEqualTo("/path");
        assertThat(menu.getIcon()).isEqualTo("icon");
        assertThat(menu.getSortOrder()).isEqualTo(9);
        assertThat(menu.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(menu.isVisible()).isFalse();
        assertThat(menu.isActive()).isFalse();
        assertThat(menu.getCreatedAt()).isEqualTo(t);
        assertThat(menu.getUpdatedAt()).isEqualTo(t);
    }
}
