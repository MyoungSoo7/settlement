package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * organization-service 조직 이벤트의 읽기 전용 프로젝션 행.
 * PK = organization_id (이벤트로 수신 — 생성 전략 없음, 멱등 UPSERT 키).
 */
@Entity
@Table(name = "org_projection")
public class OrgProjectionJpaEntity {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgProjectionJpaEntity() { }

    public OrgProjectionJpaEntity(Long organizationId, String name, String type,
                                  String externalRef, Instant updatedAt) {
        this.organizationId = organizationId;
        this.name = name;
        this.type = type;
        this.externalRef = externalRef;
        this.updatedAt = updatedAt;
    }

    public Long getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getExternalRef() { return externalRef; }
    public Instant getUpdatedAt() { return updatedAt; }
}
