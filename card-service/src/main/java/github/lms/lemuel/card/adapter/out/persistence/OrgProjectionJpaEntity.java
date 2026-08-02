package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadOrgProjectionPort.OrgView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * org_projection 테이블 매핑 (V4). PK 는 organization-service 가 할당한 organization_id 그대로
 * 쓴다(자체 채번 없음) — merge 가 있으면 UPDATE, 없으면 INSERT 하는 자연키 UPSERT 패턴.
 */
@Entity
@Table(name = "org_projection")
public class OrgProjectionJpaEntity {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected OrgProjectionJpaEntity() {
    }

    public OrgProjectionJpaEntity(Long organizationId, String name, String type, String externalRef) {
        this.organizationId = organizationId;
        this.name = name;
        this.type = type;
        this.externalRef = externalRef;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    void update(String name, String type, String externalRef) {
        this.name = name;
        this.type = type;
        this.externalRef = externalRef;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public OrgView toView() {
        return new OrgView(organizationId, type, externalRef);
    }
}
