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

    // ── 부모 검증 (순환 참조 방지) ────────────────────────────

    @Test @DisplayName("createMenu - 존재하지 않는 부모면 예외")
    void createMenu_parentNotFound() {
        when(loadMenuPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                "메뉴A", "/a", null, 99L, 0, null, true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 자기 자신을 부모로 지정하면 예외")
    void updateMenu_selfParent() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));

        assertThatThrownBy(() -> service.updateMenu(5L, new MenuUseCase.UpdateMenuCommand(
                "a", "/a", null, 5L, 0, null, true, true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 자손을 부모로 지정하면 순환 참조 예외")
    void updateMenu_descendantParent() {
        Menu root = menu(1L, null, "root", 0);
        Menu child = menu(2L, 1L, "child", 0);
        Menu grandChild = menu(3L, 2L, "grand", 0);
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(root));
        when(loadMenuPort.findAll()).thenReturn(List.of(root, child, grandChild));

        assertThatThrownBy(() -> service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                "root", "/root", null, 3L, 0, null, true, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("순환");
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 존재하지 않는 부모면 예외")
    void updateMenu_parentNotFound() {
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(menu(1L, null, "a", 0)));
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                "a", "/a", null, 99L, 0, null, true, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("updateMenu - 정상 부모 변경은 저장")
    void updateMenu_validParentChange() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, null, "b", 1);
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(a));
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b));
        when(saveMenuPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Menu updated = service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                "a", "/a", null, 2L, 0, null, true, true));

        assertThat(updated.getParentId()).isEqualTo(2L);
    }

    // ── 배치 재배치 (reorder) ─────────────────────────────────

    @Test @DisplayName("reorder - 부모/정렬순서를 적용해 일괄 저장")
    void reorder_ok() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, null, "b", 1);
        Menu c = menu(3L, 1L, "c", 0);
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b, c));
        when(saveMenuPort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Menu> saved = service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, null, 1),
                new MenuUseCase.ReorderItemCommand(2L, null, 0),
                new MenuUseCase.ReorderItemCommand(3L, 2L, 0)   // c 를 b 아래로 이동
        ));

        assertThat(saved).hasSize(3);
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(0);
        assertThat(c.getParentId()).isEqualTo(2L);
    }

    @Test @DisplayName("reorder - 빈 목록이면 저장 없이 빈 반환")
    void reorder_empty() {
        assertThat(service.reorder(List.of())).isEmpty();
        assertThat(service.reorder(null)).isEmpty();
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 존재하지 않는 메뉴 ID 면 전체 거부")
    void reorder_menuNotFound() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(99L, null, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 존재하지 않는 부모면 전체 거부")
    void reorder_parentNotFound() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 99L, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 자기 자신을 부모로 지정하면 전체 거부")
    void reorder_selfParent() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 1L, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 서로를 부모로 지정하는 순환 재배치는 전체 거부")
    void reorder_cycle() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, 1L, "b", 0);
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 2L, 0)))) // b 는 이미 a 의 자식 → 순환
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("순환");
        verify(saveMenuPort, never()).saveAll(any());
    }
}
