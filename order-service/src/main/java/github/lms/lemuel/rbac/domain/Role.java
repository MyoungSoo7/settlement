package github.lms.lemuel.rbac.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Role 도메인 모델 (순수 POJO)
 */
public class Role {

    private Long id;
    private String code;
    private String name;
    private String description;
    private boolean builtin;
    private LocalDateTime createdAt;
    private List<Permission> permissions = new ArrayList<>();

    public Role() {}

    public static Role of(Long id, String code, String name, String description,
                          boolean builtin, LocalDateTime createdAt) {
        Role role = new Role();
        role.id = id;
        role.code = code;
        role.name = name;
        role.description = description;
        role.builtin = builtin;
        role.createdAt = createdAt;
        return role;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<Permission> getPermissions() { return permissions; }
    public void setPermissions(List<Permission> permissions) {
        this.permissions = permissions == null ? new ArrayList<>() : permissions;
    }
}
