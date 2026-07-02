package github.lms.lemuel.commoncode.domain;

import java.time.LocalDateTime;

/**
 * 공통코드 그룹 도메인 모델 (순수 POJO)
 */
public class CommonCodeGroup {

    private String groupCode;
    private String name;
    private String description;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CommonCodeGroup() {
        this.active = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static CommonCodeGroup create(String groupCode, String name, String description) {
        if (groupCode == null || groupCode.isBlank()) {
            throw new IllegalArgumentException("그룹코드는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("그룹명은 필수입니다.");
        }
        CommonCodeGroup group = new CommonCodeGroup();
        group.groupCode = groupCode.trim().toUpperCase();
        group.name = name.trim();
        group.description = description;
        return group;
    }

    public void update(String name, String description, boolean active) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("그룹명은 필수입니다.");
        }
        this.name = name.trim();
        this.description = description;
        this.active = active;
        this.updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
