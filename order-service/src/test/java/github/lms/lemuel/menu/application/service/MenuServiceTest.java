package github.lms.lemuel.menu.application.service;

import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock LoadMenuPort loadMenuPort;
    @Mock SaveMenuPort saveMenuPort;
    @InjectMocks MenuService service;

    private Menu menu(Long id, Long parentId, String name, int sortOrder) {
        Menu m = Menu.create(name, "/" + name, null, parentId, sortOrder, "USER", true);
        m.setId(id);
        return m;
    }

    @Test @DisplayName("getMenuTree - 부모/자식을 조립하고 sortOrder 로 정렬")
    void getMenuTree_buildsAndSorts() {
        Menu root1 = menu(1L, null, "root1", 2);
        Menu root2 = menu(2L, null, "root2", 1);
        Menu child = menu(3L, 1L, "child", 0);
        Menu orphan = menu(4L, 99L, "orphan", 5); // 부모 없음 → 루트로 처리
        when(loadMenuPort.findAll()).thenReturn(List.of(root1, root2, child, orphan));

        List<Menu> tree = service.getMenuTree();

        // root2(1) < root1(2) < orphan(5) 순서
        assertThat(tree).extracting(Menu::getId).containsExactly(2L, 1L, 4L);
        assertThat(root1.getChildren()).extracting(Menu::getId).containsExactly(3L);
    }

    @Test @DisplayName("getAllFlat - 포트에 위임")
    void getAllFlat() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));
        assertThat(service.getAllFlat()).hasSize(1);
    }

    @Test @DisplayName("createMenu - 도메인 생성 후 저장")
    void createMenu() {
        when(saveMenuPort.save(any())).thenAnswer(inv -> {
            Menu m = inv.getArgument(0);
            m.setId(10L);
            return m;
        });

        Menu saved = service.createMenu(new MenuUseCase.CreateMenuCommand(
                "메뉴A", "/a", "icon", null, 3, "ADMIN", true));

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getName()).isEqualTo("메뉴A");
        assertThat(saved.getRequiredRole()).isEqualTo("ADMIN");
    }

    @Test @DisplayName("updateMenu - 존재하면 수정 후 저장")
    void updateMenu_success() {
        Menu existing = menu(5L, null, "old", 0);
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(existing));
        when(saveMenuPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Menu updated = service.updateMenu(5L, new MenuUseCase.UpdateMenuCommand(
                "new", "/new", "ic", null, 1, "USER", false, false));

        assertThat(updated.getName()).isEqualTo("new");
        assertThat(updated.isVisible()).isFalse();
        assertThat(updated.isActive()).isFalse();
    }

    @Test @DisplayName("updateMenu - 없으면 예외")
    void updateMenu_notFound() {
        when(loadMenuPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMenu(404L, new MenuUseCase.UpdateMenuCommand(
                "n", "/n", null, null, 0, "USER", true, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("deleteMenu - 자식이 없으면 삭제")
    void deleteMenu_success() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));
        when(loadMenuPort.existsByParentId(5L)).thenReturn(false);

        service.deleteMenu(5L);

        verify(saveMenuPort).deleteById(5L);
    }

    @Test @DisplayName("deleteMenu - 자식이 있으면 예외")
    void deleteMenu_hasChildren() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));
        when(loadMenuPort.existsByParentId(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteMenu(5L))
                .isInstanceOf(IllegalStateException.class);
        verify(saveMenuPort, never()).deleteById(any());
    }

    @Test @DisplayName("deleteMenu - 없으면 예외")
    void deleteMenu_notFound() {
        when(loadMenuPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMenu(404L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
