package github.lms.lemuel.menu.domain;
import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuTest {

    @Test @DisplayName("create - 이름 앞뒤 공백 트림 및 기본값 설정")
    void create_trimsName() {
        Menu menu = Menu.create("  대시보드  ", "/dash", "icon", 1L, 3, "ADMIN", true);

        assertThat(menu.getName()).isEqualTo("대시보드");
        assertThat(menu.getPath()).isEqualTo("/dash");
        assertThat(menu.getParentId()).isEqualTo(1L);
        assertThat(menu.getSortOrder()).isEqualTo(3);
        assertThat(menu.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(menu.isVisible()).isTrue();
        assertThat(menu.isActive()).isTrue(); // 기본 생성자 default
        assertThat(menu.getChildren()).isEmpty();
        assertThat(menu.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create - 이름이 비어 있으면 예외")
    void create_blankName() {
        assertThatThrownBy(() -> Menu.create("  ", "/p", null, null, 0, null, true))
                .isInstanceOf(MenuInvariantViolationException.class);
        assertThatThrownBy(() -> Menu.create(null, "/p", null, null, 0, null, true))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("update - 필드 전체 갱신")
    void update() {
        Menu menu = Menu.create("old", "/old", null, null, 0, null, true);
        menu.update("new", "/new", "ic", 2L, 5, "USER", false, false);

        assertThat(menu.getName()).isEqualTo("new");
        assertThat(menu.getPath()).isEqualTo("/new");
        assertThat(menu.getIcon()).isEqualTo("ic");
        assertThat(menu.getParentId()).isEqualTo(2L);
        assertThat(menu.getSortOrder()).isEqualTo(5);
        assertThat(menu.getRequiredRole()).isEqualTo("USER");
        assertThat(menu.isVisible()).isFalse();
        assertThat(menu.isActive()).isFalse();
    }

    @Test @DisplayName("update - 이름이 비어 있으면 예외")
    void update_blankName() {
        Menu menu = Menu.create("old", "/old", null, null, 0, null, true);
        assertThatThrownBy(() -> menu.update("", "/n", null, null, 0, null, true, true))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("addChild - 자식 목록에 추가")
    void addChild() {
        Menu parent = Menu.create("p", "/p", null, null, 0, null, true);
        Menu child = Menu.create("c", "/c", null, null, 0, null, true);
        parent.addChild(child);

        assertThat(parent.getChildren()).containsExactly(child);
    }

    @Test @DisplayName("setters - 식별자/시간 세팅")
    void setters() {
        Menu menu = new Menu();
        menu.assignId(9L);
        menu.replaceChildren(new java.util.ArrayList<>());
        assertThat(menu.getId()).isEqualTo(9L);
        assertThat(menu.getChildren()).isEmpty();
    }
}
