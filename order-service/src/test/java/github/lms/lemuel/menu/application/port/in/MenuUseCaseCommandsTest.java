package github.lms.lemuel.menu.application.port.in;

import github.lms.lemuel.menu.application.port.in.MenuUseCase.CreateMenuCommand;
import github.lms.lemuel.menu.application.port.in.MenuUseCase.UpdateMenuCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MenuUseCaseCommandsTest {

    @Test @DisplayName("CreateMenuCommand - 접근자 값 보존")
    void createCommand() {
        CreateMenuCommand cmd = new CreateMenuCommand("이름", "/path", "icon", 1L, 3, "ADMIN", true);

        assertThat(cmd.name()).isEqualTo("이름");
        assertThat(cmd.path()).isEqualTo("/path");
        assertThat(cmd.icon()).isEqualTo("icon");
        assertThat(cmd.parentId()).isEqualTo(1L);
        assertThat(cmd.sortOrder()).isEqualTo(3);
        assertThat(cmd.requiredRole()).isEqualTo("ADMIN");
        assertThat(cmd.visible()).isTrue();
    }

    @Test @DisplayName("UpdateMenuCommand - 접근자 값 보존")
    void updateCommand() {
        UpdateMenuCommand cmd = new UpdateMenuCommand("이름", "/path", "icon", 2L, 5, "USER", false, true);

        assertThat(cmd.name()).isEqualTo("이름");
        assertThat(cmd.path()).isEqualTo("/path");
        assertThat(cmd.icon()).isEqualTo("icon");
        assertThat(cmd.parentId()).isEqualTo(2L);
        assertThat(cmd.sortOrder()).isEqualTo(5);
        assertThat(cmd.requiredRole()).isEqualTo("USER");
        assertThat(cmd.visible()).isFalse();
        assertThat(cmd.active()).isTrue();
    }
}
