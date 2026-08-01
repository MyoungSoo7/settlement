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
            //   저장 시점을 근사치로 남긴다 — 근거 필드(재원·비율·등급·산식)가 이 시점의 값이라는 뜻이다.
            //   같은 스냅샷을 담은 채로 다시 저장돼도(예: suspend 후 재조회·재저장) screened_at 이 갱신되는
            //   한계가 있다 — 정확한 "최초 심사 시각" 보존이 필요해지면 Task 9 가 도메인에 시각 필드를
            //   추가하거나, 어댑터가 기존 행을 먼저 읽어 스냅샷 동일 여부로 보존 여부를 판단해야 한다.
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
