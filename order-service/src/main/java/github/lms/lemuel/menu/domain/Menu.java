package github.lms.lemuel.menu.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Menu Domain Entity (순수 POJO)
 */
public class Menu {

    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String icon;
    private int sortOrder;
    private String requiredRole;
    private boolean visible;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 트리 조립용 (DB 컬럼 아님)
    private List<Menu> children = new ArrayList<>();

    public Menu() {
        this.visible = true;
        this.active = true;
        this.sortOrder = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Menu create(String name, String path, String icon,
                              Long parentId, int sortOrder, String requiredRole, boolean visible) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("메뉴 이름은 필수입니다.");
        }
        Menu menu = new Menu();
        menu.name = name.trim();
        menu.path = path;
        menu.icon = icon;
        menu.parentId = parentId;
        menu.sortOrder = sortOrder;
        menu.requiredRole = requiredRole;
        menu.visible = visible;
        return menu;
    }

    public void update(String name, String path, String icon,
                       Long parentId, int sortOrder, String requiredRole,
                       boolean visible, boolean active) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("메뉴 이름은 필수입니다.");
        }
        this.name = name.trim();
        this.path = path;
        this.icon = icon;
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.requiredRole = requiredRole;
        this.visible = visible;
        this.active = active;
        this.updatedAt = LocalDateTime.now();
    }

    public void addChild(Menu child) {
        this.children.add(child);
    }

    /**
     * 트리 재정렬 시 부모/정렬순서 재배치(setter 대체). updatedAt 갱신까지 한 동작으로 묶는다.
     */
    public void reorder(Long parentId, int sortOrder) {
        this.parentId = parentId;
        this.sortOrder = sortOrder;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 트리 조립용 children 교체(DB 컬럼 아님, 조회 시 조립).
     */
    public void replaceChildren(List<Menu> children) {
        this.children = children != null ? children : new ArrayList<>();
    }

    /**
     * 영속 레코드 복원 팩토리. 어댑터가 no-arg + setter 대신 이 경로로만 도메인을 재구성한다.
     */
    public static Menu rehydrate(Long id, Long parentId, String name, String path, String icon,
                                 int sortOrder, String requiredRole, boolean visible, boolean active,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        Menu menu = new Menu();
        menu.id = id;
        menu.parentId = parentId;
        menu.name = name;
        menu.path = path;
        menu.icon = icon;
        menu.sortOrder = sortOrder;
        menu.requiredRole = requiredRole;
        menu.visible = visible;
        menu.active = active;
        menu.createdAt = createdAt;
        menu.updatedAt = updatedAt;
        return menu;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    // Getters
    public Long getId() { return id; }

    public Long getParentId() { return parentId; }

    public String getName() { return name; }

    public String getPath() { return path; }

    public String getIcon() { return icon; }

    public int getSortOrder() { return sortOrder; }

    public String getRequiredRole() { return requiredRole; }

    public boolean isVisible() { return visible; }

    public boolean isActive() { return active; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<Menu> getChildren() { return children; }
}
