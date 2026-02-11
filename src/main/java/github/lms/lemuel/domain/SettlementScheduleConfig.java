package github.lms.lemuel.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 정산 스케줄 동적 설정
 * 업체별로 다른 정산 주기를 DB로 관리
 */
@Entity
@Table(name = "settlement_schedule_config")
@Getter
@Setter
public class SettlementScheduleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey; // SETTLEMENT_CREATE, SETTLEMENT_CONFIRM, ADJUSTMENT_CONFIRM

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression; // 예: "0 0 2 * * *" (매일 새벽 2시)

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "merchant_id")
    private Long merchantId; // null이면 전체 적용, 특정 merchant_id면 해당 업체만 적용

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
