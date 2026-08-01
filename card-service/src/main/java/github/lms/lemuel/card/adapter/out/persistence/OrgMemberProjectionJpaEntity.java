package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * organization-service 멤버십 이벤트의 읽기 전용 프로젝션 행.
 * 복합 PK = (organization_id, user_id). 제거는 행 삭제가 아니라 {@code active=false}
 * 툼스톤으로 남겨 순서 역전·재수신에 안전하다.
 */
@Entity
@Table(name = "org_member_projection")
@IdClass(OrgMemberProjectionJpaEntity.MemberKey.class)
public class OrgMemberProjectionJpaEntity {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgMemberProjectionJpaEntity() { }

    public OrgMemberProjectionJpaEntity(Long organizationId, Long userId, String role,
                                        boolean active, Instant updatedAt) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    public Long getOrganizationId() { return organizationId; }
    public Long getUserId() { return userId; }
    public String getRole() { return role; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 복합 키 — JPA @IdClass 계약(기본 생성자·equals/hashCode). */
    public static class MemberKey implements Serializable {
        private Long organizationId;
        private Long userId;

        public MemberKey() { }

        public MemberKey(Long organizationId, Long userId) {
            this.organizationId = organizationId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemberKey that)) return false;
            return Objects.equals(organizationId, that.organizationId)
                    && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, userId);
        }
    }
}
