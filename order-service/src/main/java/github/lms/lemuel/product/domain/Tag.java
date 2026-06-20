package github.lms.lemuel.product.domain;

import java.time.LocalDateTime;

/**
 * Tag Domain Entity (순수 POJO)
 * 상품에 부착할 수 있는 태그 (예: 신상품, 베스트, 할인 등)
 */
public class Tag {

    private Long id;
    private String name;
    private String color; // HEX 색상 코드 (예: #EF4444)
    private LocalDateTime createdAt;

    // 기본 생성자
    public Tag() {
        this.color = "#6B7280"; // 기본 회색
        this.createdAt = LocalDateTime.now();
    }

    // 전체 생성자
    public Tag(Long id, String name, String color, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.color = color != null ? color : "#6B7280";
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    // 정적 팩토리 메서드
    public static Tag create(String name, String color) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor(color);
        tag.validateName();
        tag.validateColor();
        return tag;
    }

    // 도메인 규칙: name 검증
    public void validateName() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tag name cannot be empty");
        }
        if (name.length() > 50) {
            throw new IllegalArgumentException("Tag name must not exceed 50 characters");
        }
    }

    // 도메인 규칙: color 검증 (HEX 형식)
    public void validateColor() {
        if (color == null || !color.matches("^#[0-9A-Fa-f]{6}$")) {
            throw new IllegalArgumentException("Tag color must be a valid HEX color code (e.g., #EF4444)");
        }
    }

    // 비즈니스 메서드: 태그 정보 업데이트
    public void updateInfo(String name, String color) {
        if (name != null && !name.trim().isEmpty()) {
            this.name = name;
            validateName();
        }
        if (color != null) {
            this.color = color;
            validateColor();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
