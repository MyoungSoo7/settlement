package github.lms.lemuel.menu.application.service;

import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MenuService implements MenuUseCase {

    private final LoadMenuPort loadMenuPort;
    private final SaveMenuPort saveMenuPort;

    @Override
    @Transactional(readOnly = true)
    public List<Menu> getMenuTree() {
        List<Menu> all = loadMenuPort.findAll();
        return buildTree(all);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Menu> getAllFlat() {
        return loadMenuPort.findAll();
    }

    @Override
    public Menu createMenu(CreateMenuCommand command) {
        Menu menu = Menu.create(
                command.name(),
                command.path(),
                command.icon(),
                command.parentId(),
                command.sortOrder(),
                command.requiredRole(),
                command.visible()
        );
        Menu saved = saveMenuPort.save(menu);
        log.info("메뉴 생성: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Override
    public Menu updateMenu(Long id, UpdateMenuCommand command) {
        Menu menu = loadMenuPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("메뉴를 찾을 수 없습니다: " + id));
        menu.update(
                command.name(),
                command.path(),
                command.icon(),
                command.parentId(),
                command.sortOrder(),
                command.requiredRole(),
                command.visible(),
                command.active()
        );
        Menu saved = saveMenuPort.save(menu);
        log.info("메뉴 수정: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Override
    public void deleteMenu(Long id) {
        loadMenuPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("메뉴를 찾을 수 없습니다: " + id));
        if (loadMenuPort.existsByParentId(id)) {
            throw new IllegalStateException("하위 메뉴가 존재하여 삭제할 수 없습니다. 하위 메뉴를 먼저 삭제하세요.");
        }
        saveMenuPort.deleteById(id);
        log.info("메뉴 삭제: id={}", id);
    }

    /**
     * 평면 목록을 sort_order 기준 트리로 조립한다 (메모리 내 재귀).
     */
    private List<Menu> buildTree(List<Menu> all) {
        Map<Long, Menu> byId = all.stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));

        List<Menu> roots = new ArrayList<>();
        for (Menu menu : all) {
            if (menu.getParentId() == null) {
                roots.add(menu);
            } else {
                Menu parent = byId.get(menu.getParentId());
                if (parent != null) {
                    parent.addChild(menu);
                } else {
                    // 부모가 없는 고아 메뉴는 루트로 처리
                    roots.add(menu);
                }
            }
        }

        roots.sort(java.util.Comparator.comparingInt(Menu::getSortOrder));
        sortChildren(roots);
        return roots;
    }

    private void sortChildren(List<Menu> menus) {
        for (Menu menu : menus) {
            menu.getChildren().sort(java.util.Comparator.comparingInt(Menu::getSortOrder));
            sortChildren(menu.getChildren());
        }
    }
}
