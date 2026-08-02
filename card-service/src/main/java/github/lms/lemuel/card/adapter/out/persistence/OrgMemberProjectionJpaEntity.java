package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * org_member_projection 테이블 매핑 (V4). 복합 PK(organization_id, user_id) — organization-service
 * membership_id 가 아니라 (조직, 사용자) 쌍이 이 프로젝션의 자연키다(shared-common
 * {@code ProcessedEventJpaEntity.ProcessedEventId} 와 동일한 {@code @EmbeddedId} 관례).
 *
 * <p>{@code active=false} 는 "조직에서 제거됨"을 뜻한다 — 행을 삭제하지 않는 이유는 이력 보존
 * (누가 언제 어떤 역할이었는지)과, 재합류 시 멱등 UPSERT 를 단순하게 유지하기 위함이다.
 */
@Entity
@Table(name = "org_member_projection")
public class OrgMemberProjectionJpaEntity {

    @EmbeddedId
    private OrgMemberProjectionId id;

    /** 톰스톤(제거 선착) 행은 역할을 모른 채 만들어질 수 있어 nullable — 활성 행은 항상 채운다. */
    @Column(length = 20)
    private String role;

    @Column(nullable = false)
    private boolean active;

    /**
     * organization-service 멤버십 id — 토픽 간 순서 역전 방어의 세대 번호.
     * REMOVED 는 멤버십의 터미널이고 재합류는 새(더 큰) id 를 받는다. V5 이전 적재분은 null.
     */
    @Column(name = "membership_id")
    private Long membershipId;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected OrgMemberProjectionJpaEntity() {
    }

    public OrgMemberProjectionJpaEntity(Long organizationId, Long userId, String role, boolean active) {
        this.id = new OrgMemberProjectionId(organizationId, userId);
        this.role = role;
        this.active = active;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public OrgMemberProjectionId getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Long getMembershipId() {
        return membershipId;
    }

    void setRole(String role) {
        this.role = role;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setMembershipId(Long membershipId) {
        this.membershipId = membershipId;
    }

    @Embeddable
    public static class OrgMemberProjectionId implements Serializable {

        @Column(name = "organization_id")
        private Long organizationId;

        @Column(name = "user_id")
        private Long userId;

        protected OrgMemberProjectionId() {
        }

        public OrgMemberProjectionId(Long organizationId, Long userId) {
            this.organizationId = organizationId;
            this.userId = userId;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public Long getUserId() {
            return userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OrgMemberProjectionId that)) {
                return false;
            }
            return Objects.equals(organizationId, that.organizationId) && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, userId);
        }
    }
}
