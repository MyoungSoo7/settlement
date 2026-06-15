package github.lms.lemuel.reservation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 기사 자격 read-projection (technician_view).
 * user-service 멤버십 이벤트로 upsert 되며, 배정 검증 전용으로만 읽는다.
 */
@Entity
@Table(name = "technician_view")
@Getter
@Setter
@NoArgsConstructor
public class TechnicianViewJpaEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "membership_status", nullable = false, length = 20)
    private String membershipStatus;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
