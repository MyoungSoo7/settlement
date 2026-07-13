package github.lms.lemuel.menu.application.service;

import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;
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
        if (command.parentId() != null) {
            loadMenuPort.findById(command.parentId())
                    .orElseThrow(() -> new MenuInvariantViolationException(
                            "존재하지 않는 부모 메뉴: " + command.parentId()));
        }
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
                .orElseThrow(() -> new MenuInvariantViolationException("메뉴를 찾을 수 없습니다: " + id));
        validateParentChange(id, command.parentId());
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
                .orElseThrow(() -> new MenuInvariantViolationException("메뉴를 찾을 수 없습니다: " + id));
        if (loadMenuPort.existsByParentId(id)) {
            throw new MenuInvariantViolationException("하위 메뉴가 존재하여 삭제할 수 없습니다. 하위 메뉴를 먼저 삭제하세요.");
        }
        saveMenuPort.deleteById(id);
        log.info("메뉴 삭제: id={}", id);
    }

    @Override
    public List<Menu> reorder(List<ReorderItemCommand> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<Long, Menu> byId = loadMenuPort.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));

        // 1) 변경 적용 (메모리 상 도메인 객체 — 검증 실패 시 저장 없이 전체 거부)
        for (ReorderItemCommand item : items) {
            Menu menu = byId.get(item.id());
            if (menu == null) {
                throw new MenuInvariantViolationException("존재하지 않는 메뉴 ID: " + item.id());
            }
            if (item.parentId() != null && !byId.containsKey(item.parentId())) {
                throw new MenuInvariantViolationException("존재하지 않는 부모 메뉴: " + item.parentId());
            }
            if (item.parentId() != null && item.parentId().equals(item.id())) {
                throw new MenuInvariantViolationException("자기 자신을 부모로 지정할 수 없습니다: " + item.id());
            }
            menu.reorder(item.parentId(), item.sortOrder());
        }

        // 2) 변경 반영된 그래프 전체에 대해 순환 참조 검증
        for (Menu menu : byId.values()) {
            java.util.Set<Long> visited = new java.util.HashSet<>();
            Long cursor = menu.getParentId();
            while (cursor != null) {
                if (cursor.equals(menu.getId()) || !visited.add(cursor)) {
                    throw new MenuInvariantViolationException(
                            "순환 참조가 발생하는 재배치입니다: menuId=" + menu.getId());
                }
                Menu current = byId.get(cursor);
                cursor = current == null ? null : current.getParentId();
            }
        }

        // 3) 요청에 포함된 메뉴만 저장
        List<Menu> changed = items.stream()
                .map(i -> byId.get(i.id()))
                .distinct()
                .collect(Collectors.toList());
        List<Menu> saved = saveMenuPort.saveAll(changed);
        log.info("메뉴 재배치: count={}", saved.size());
        return saved;
    }

    /**
     * 부모 변경 검증 — 자기 자신/자손을 부모로 지정하면 순환 참조가 생기므로 거부한다.
     */
    private void validateParentChange(Long id, Long newParentId) {
        if (newParentId == null) {
            return;
        }
        if (newParentId.equals(id)) {
            throw new MenuInvariantViolationException("자기 자신을 부모로 지정할 수 없습니다: " + id);
        }
        Map<Long, Menu> byId = loadMenuPort.findAll().stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));
        if (!byId.containsKey(newParentId)) {
            throw new MenuInvariantViolationException("존재하지 않는 부모 메뉴: " + newParentId);
        }
        // 새 부모의 조상 체인에 자신이 있으면 자손을 부모로 지정한 것 → 순환
        java.util.Set<Long> visited = new java.util.HashSet<>();
        Long cursor = newParentId;
        while (cursor != null) {
            if (cursor.equals(id)) {
                throw new MenuInvariantViolationException(
                        "하위 메뉴를 부모로 지정하면 순환 참조가 발생합니다: " + newParentId);
            }
            if (!visited.add(cursor)) {
                break; // 기존 데이터 이상으로 인한 무한루프 방지
            }
            Menu current = byId.get(cursor);
            cursor = current == null ? null : current.getParentId();
        }
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
