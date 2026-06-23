package github.lms.lemuel.rbac.domain;

/**
 * Permission 도메인 모델 (순수 POJO)
 */
public class Permission {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String description;

    public Permission() {}

    public static Permission of(Long id, String code, String name,
                                String category, String description) {
        Permission p = new Permission();
        p.id = id;
        p.code = code;
        p.name = name;
        p.category = category;
        p.description = description;
        return p;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
