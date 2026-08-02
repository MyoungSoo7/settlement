package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * card_accounts 테이블 매핑 (V4). created_at/updated_at 은 DB DEFAULT NOW() 에 위임(insertable=false) —
 * 어댑터가 도메인 스냅샷으로 detached 엔티티를 재구성해 merge 하므로 감사 컬럼을 덮어쓰지 않게 한다
 * (organization-service {@code OrganizationJpaEntity} 와 동일 관례).
 */
@Entity
@Table(name = "card_accounts")
public class CardAccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "seller_id", nullable = false, length = 64)
    private String sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardAccountStatus status;

    @Column(name = "master_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal masterLimit;

    // ── 한도 산정 근거 스냅샷 — LimitSnapshot 이 있을 때만 전부 채워지고, 없으면 전부 null ──
    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "seller_payable_snap", precision = 19, scale = 2)
    private BigDecimal sellerPayableSnap;

    @Column(name = "holdback_payable_snap", precision = 19, scale = 2)
    private BigDecimal holdbackPayableSnap;

    @Column(name = "applied_ratio", precision = 5, scale = 4)
    private BigDecimal appliedRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "reputation_grade", length = 2)
    private ReputationGrade reputationGrade;

    @Column(name = "limit_formula", length = 200)
    private String limitFormula;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CardAccountJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static CardAccountJpaEntity fromDomain(CardAccount a) {
        CardAccountJpaEntity e = new CardAccountJpaEntity();
        e.id = a.getId();
        e.organizationId = a.getOrganizationId();
        e.sellerId = a.getSellerId();
        e.status = a.getStatus();
        e.masterLimit = a.getMasterLimit();
        LimitSnapshot snapshot = a.getLimitSnapshot();
        if (snapshot != null) {
            // ★ 도메인 LimitSnapshot 은 산정 "시각"을 보관하지 않는다(activate/reject 는 시각을 받지 않음).
            //   여기서는 일단 "지금"으로 잠정 채운다 — 실제로 기존 행과 스냅샷이 같은지 비교해
            //   screened_at 을 보존할지 갱신할지 최종 결정하는 것은 어댑터(CardAccountPersistenceAdapter)의
            //   책임이다(hasSameLimitSnapshot/getScreenedAt/setScreenedAt 참조). fromDomain 단독으로는
            //   "이게 기존과 같은 스냅샷인지" 알 수 없어(비교 대상 없음) 여기서 최종 판단을 못 내린다.
            e.screenedAt = Instant.now();
            e.sellerPayableSnap = snapshot.sellerPayable();
            e.holdbackPayableSnap = snapshot.holdbackPayable();
            e.appliedRatio = snapshot.appliedRatio();
            e.reputationGrade = snapshot.reputationGrade();
            e.limitFormula = snapshot.formula();
        }
        e.rejectReason = a.getRejectReason();
        e.version = a.getVersion();
        return e;
    }

    /**
     * 스냅샷 근거 5개 필드가 {@code other} 와 동일한지 비교한다 — 어댑터가 "재심사로 실제 값이
     * 바뀐 저장"과 "심사와 무관한 상태 변경(suspend/resume/close/changeMasterLimit)으로 인한
     * 재저장"을 구별해 {@code screened_at} 을 보존할지 갱신할지 판단하는 데 쓴다.
     *
     * <p>금액 필드는 {@link BigDecimal#compareTo}로 비교한다 — {@code equals}는 scale(소수 자릿수)이
     * 다르면 값이 같아도 false 를 반환하는데, DB 왕복 후(NUMERIC(19,2))에는 저장 전 값과 scale 이
     * 달라질 수 있어(예: "0.7" vs "0.7000") equals 를 쓰면 사실상 항상 "달라짐"으로 오판, 이 비교
     * 자체가 무의미해진다.
     */
    boolean hasSameLimitSnapshot(CardAccountJpaEntity other) {
        return bigDecimalEquals(this.sellerPayableSnap, other.sellerPayableSnap)
                && bigDecimalEquals(this.holdbackPayableSnap, other.holdbackPayableSnap)
                && bigDecimalEquals(this.appliedRatio, other.appliedRatio)
                && Objects.equals(this.reputationGrade, other.reputationGrade)
                && Objects.equals(this.limitFormula, other.limitFormula);
    }

    private static boolean bigDecimalEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    Instant getScreenedAt() {
        return screenedAt;
    }

    void setScreenedAt(Instant v) {
        this.screenedAt = v;
    }

    public CardAccount toDomain() {
        CardAccount.Builder b = CardAccount.builder()
                .id(id)
                .organizationId(organizationId)
                .sellerId(sellerId)
                .status(status)
                .masterLimit(masterLimit)
                .rejectReason(rejectReason)
                .version(version);
        // LimitSnapshot compact 생성자는 sellerPayable/holdbackPayable/appliedRatio/reputationGrade
        // 전부 non-null 을 강제한다 — fromDomain 이 항상 4개를 함께 쓰므로 이 4개가 함께 null 이거나
        // 함께 채워져 있어야 한다(부분 저장 없음).
        if (sellerPayableSnap != null && holdbackPayableSnap != null
                && appliedRatio != null && reputationGrade != null) {
            b.limitSnapshot(new LimitSnapshot(sellerPayableSnap, holdbackPayableSnap, appliedRatio,
                    reputationGrade, limitFormula));
        }
        return b.build();
    }

    public Long getId() {
        return id;
    }
}
