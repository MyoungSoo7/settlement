package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * settlement 소유 사용자 프로젝션 (ADR 0020 Phase 3b).
 * order users(email)를 @Immutable 매핑하던 {@code SettlementUserReadModel} 을 대체한다.
 * UserRegistered 이벤트로 적재되며 settlement 가 소유한다.
 */
@Entity
@Table(name = "settlement_user_view")
@Getter
@Setter
@NoArgsConstructor
public class SettlementUserViewJpaEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(length = 255)
    private String email;

    /**
     * 셀러 등급 (ADR 0031 통지 반영) — <b>조회·리포트 전용</b>이다.
     *
     * <p>정산 금액 계산에는 쓰지 않는다. 정산은 결제 시점 등급({@code PaymentCaptured} 동봉값 →
     * {@code settlement_payment_view.seller_tier})을 쓰며, 등급 변경은 이후 결제분부터 반영된다
     * (비소급). 여기 값을 계산에 끌어다 쓰면 과거 정산이 조용히 재해석된다.
     */
    @Column(name = "seller_tier", length = 20)
    private String sellerTier;

    @Column(name = "tier_effective_from")
    private LocalDate tierEffectiveFrom;

    @Column(name = "updated_at", nullable = false)
    /** 적재된 "순간" — 시간대를 잃지 않게 timestamptz/OffsetDateTime (DATA-STANDARD N1). */
    private OffsetDateTime updatedAt;
}
