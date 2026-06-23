package github.lms.lemuel.commoncode.domain;

import java.time.LocalDateTime;

/**
 * 공통코드 항목 도메인 모델 (순수 POJO)
 */
public class CommonCode {

    private Long id;
    private String groupCode;
    private String code;
    private String label;
    private int sortOrder;
    private boolean active;
    private String extra1;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CommonCode() {
        this.active = true;
        this.sortOrder = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static CommonCode create(String groupCode, String code, String label, int sortOrder, String extra1) {
        if (groupCode == null || groupCode.isBlank()) {
            throw new IllegalArgumentException("그룹코드는 필수입니다.");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("코드는 필수입니다.");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("코드명(label)은 필수입니다.");
        }
        CommonCode commonCode = new CommonCode();
        commonCode.groupCode = groupCode.trim().toUpperCase();
        commonCode.code = code.trim().toUpperCase();
        commonCode.label = label.trim();
        commonCode.sortOrder = sortOrder;
        commonCode.extra1 = extra1;
        return commonCode;
    }

    public void update(String label, int sortOrder, boolean active, String extra1) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("코드명(label)은 필수입니다.");
        }
        this.label = label.trim();
        this.sortOrder = sortOrder;
        this.active = active;
        this.extra1 = extra1;
        this.updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String groupCode) { this.groupCode = groupCode; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getExtra1() { return extra1; }
    public void setExtra1(String extra1) { this.extra1 = extra1; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
