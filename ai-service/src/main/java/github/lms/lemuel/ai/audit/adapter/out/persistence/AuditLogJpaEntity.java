package github.lms.lemuel.ai.audit.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.LocalDateTime;

/**
 * audit_logs 매핑 엔티티 (경량 자체 구현).
 *
 * <p>ai-service 는 shared-common 을 의존하지만 제한 스캔(common.config.jwt 만)이라 공통 audit
 * 스택(common.audit.AuditLogJpaEntity)이 컨텍스트에 없어 재사용할 수 없다 — 컬럼 구성을 동일하게 복제한다
 * (V20260715152000__ai_audit_logs.sql 표준).
 *
 * <p>★ @Id 단일 매핑 근거: 물리 테이블은 월별 RANGE 파티션이라 PK 가 복합키(id, created_at)지만,
 * Hibernate {@code ddl-auto=validate} 는 매핑된 컬럼의 존재·타입만 검증하고 PK 구성은 강제하지 않는다.
 * 애플리케이션은 감사행을 INSERT 만 하고 단건 조회하지 않으므로 {@code id} 단일 @Id 로 충분하다
 * (order/loan/operation 의 파티션드 audit_logs 도 동일하게 단일 @Id 로 validate 통과 — 선례).
 * BIGSERIAL 시퀀스는 부모 테이블에 있고 각 파티션이 상속하므로 IDENTITY 삽입도 정상 동작한다.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    // Postgres JSONB — VARCHAR → JSONB 암시 캐스트 불가라 write 시 명시 cast.
    @Column(name = "detail_json", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String detailJson;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
