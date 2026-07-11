package github.lms.lemuel.menu.application.port.in;

import github.lms.lemuel.menu.domain.Menu;

import java.util.List;

public interface MenuUseCase {

    /** 전체 메뉴를 트리 구조로 반환 (sort_order 정렬) */
    List<Menu> getMenuTree();

    /** 평면 목록 반환 (부모 선택용) */
    List<Menu> getAllFlat();

    /** 메뉴 생성 */
    Menu createMenu(CreateMenuCommand command);

    /** 메뉴 수정 */
    Menu updateMenu(Long id, UpdateMenuCommand command);

    /** 메뉴 삭제 (자식 있으면 거부) */
    void deleteMenu(Long id);

    /**
     * 배치 재배치 — 여러 메뉴의 부모/정렬순서를 한 번에 저장한다.
     * 순환 참조가 발생하는 재배치는 전체 거부된다.
     *
     * @return 변경 저장된 메뉴 목록
     */
    List<Menu> reorder(List<ReorderItemCommand> items);

    record ReorderItemCommand(
            Long id,
            Long parentId,
            int sortOrder
    ) {}

    record CreateMenuCommand(
            String name,
            String path,
            String icon,
            Long parentId,
            int sortOrder,
            String requiredRole,
            boolean visible
    ) {}

    record UpdateMenuCommand(
            String name,
            String path,
            String icon,
            Long parentId,
            int sortOrder,
            String requiredRole,
            boolean visible,
            boolean active
    ) {}
}
