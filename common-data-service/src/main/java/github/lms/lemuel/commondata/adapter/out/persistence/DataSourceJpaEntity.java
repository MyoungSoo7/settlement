package github.lms.lemuel.commondata.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;

/**
 * 등록된 데이터소스 — 도메인 변환(Map/List ↔ JSON/CSV 문자열)은
 * {@link CommonDataPersistenceAdapter} 가 담당한다.
 */
@Entity
@Table(name = "data_sources")
public class DataSourceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "endpoint", nullable = false, length = 500)
    private String endpoint;

    /** 호출 시 항상 붙는 쿼리 파라미터 JSON 오브젝트 원문. */
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "default_params", nullable = false)
    private String defaultParams;

    /** 자연키 필드명 콤마 구분 목록 (null 허용 — payload 해시 키 사용). */
    @Column(name = "key_fields", length = 300)
    private String keyFields;

    @Column(name = "page_size", nullable = false)
    private int pageSize;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DataSourceJpaEntity() {
    }

    Long getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    String getEndpoint() {
        return endpoint;
    }

    String getDefaultParams() {
        return defaultParams;
    }

    String getKeyFields() {
        return keyFields;
    }

    int getPageSize() {
        return pageSize;
    }

    boolean isEnabled() {
        return enabled;
    }

    String getDescription() {
        return description;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    static DataSourceJpaEntity create(String code) {
        DataSourceJpaEntity entity = new DataSourceJpaEntity();
        entity.code = code;
        return entity;
    }

    void apply(String name, String endpoint, String defaultParams, String keyFields,
               int pageSize, boolean enabled, String description) {
        this.name = name;
        this.endpoint = endpoint;
        this.defaultParams = defaultParams;
        this.keyFields = keyFields;
        this.pageSize = pageSize;
        this.enabled = enabled;
        this.description = description;
        this.updatedAt = Instant.now();
    }
}
