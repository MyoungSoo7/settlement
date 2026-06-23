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

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<Menu> getChildren() { return children; }
    public void setChildren(List<Menu> children) { this.children = children; }
}
