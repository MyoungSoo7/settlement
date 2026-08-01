package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 카드계정 영속 엔티티.
 *
 * <p>매핑 규약(organization-service 동형): {@code created_at}/{@code updated_at} 은 DB
 * {@code DEFAULT NOW()} 에 위임하고 {@code insertable=false} — 어댑터가 도메인 스냅샷으로
 * detached 엔티티를 재구성해 merge 하므로, 이 조합이 아니면 감사 컬럼이 null 로 덮인다.
 * {@code @Version} 낙관 락으로 동시 갱신 유실을 차단한다.
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

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "master_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal masterLimit;

    @Column(name = "seller_payable_snap", precision = 19, scale = 2)
    private BigDecimal sellerPayableSnap;

    @Column(name = "holdback_payable_snap", precision = 19, scale = 2)
    private BigDecimal holdbackPayableSnap;

    @Column(name = "applied_ratio", precision = 5, scale = 4)
    private BigDecimal appliedRatio;

    @Column(name = "reputation_grade", length = 2)
    private String reputationGrade;

    @Column(name = "limit_formula", length = 200)
    private String limitFormula;

    @Column(name = "reject_reason", length = 300)
    private String rejectReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CardAccountJpaEntity() { }

    /** 도메인 스냅샷 → detached 엔티티 재구성(신규는 id null·version 0). */
    static CardAccountJpaEntity from(CardAccount account) {
        CardAccountJpaEntity e = new CardAccountJpaEntity();
        e.id = account.getId();
        e.organizationId = account.getOrganizationId();
        e.sellerId = account.getSellerId();
        e.status = account.getStatus().name();
        e.masterLimit = account.getMasterLimit();
        LimitSnapshot snap = account.getLimitSnapshot();
        if (snap != null) {
            e.sellerPayableSnap = snap.sellerPayable();
            e.holdbackPayableSnap = snap.holdbackPayable();
            e.appliedRatio = snap.appliedRatio();
            e.reputationGrade = snap.reputationGrade().name();
            e.limitFormula = snap.formula();
        }
        e.rejectReason = account.getRejectReason();
        e.version = account.getVersion();
        return e;
    }

    CardAccount toDomain() {
        LimitSnapshot snap = sellerPayableSnap == null ? null
                : new LimitSnapshot(sellerPayableSnap, holdbackPayableSnap, appliedRatio,
                        ReputationGrade.valueOf(reputationGrade), limitFormula);
        return CardAccount.builder()
                .id(id)
                .organizationId(organizationId)
                .sellerId(sellerId)
                .status(CardAccountStatus.valueOf(status))
                .masterLimit(masterLimit)
                .limitSnapshot(snap)
                .rejectReason(rejectReason)
                .version(version)
                .build();
    }

    Long getId() { return id; }
}
