package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardStatus;
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
 * cards 테이블 매핑 (V4). created_at/updated_at 은 DB DEFAULT NOW() 에 위임(insertable=false) —
 * {@code CardAccountJpaEntity} 와 동일 관례. card_account_id 는 FK 컬럼을 원시값으로만 보관한다
 * (연관관계 매핑 없음 — organization-service {@code MembershipJpaEntity} 와 동형).
 */
@Entity
@Table(name = "cards")
public class CardJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "holder_user_id", nullable = false)
    private Long holderUserId;

    @Column(name = "masked_card_no", nullable = false, length = 32)
    private String maskedCardNo;

    @Column(name = "sub_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal subLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CardJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static CardJpaEntity fromDomain(Card c) {
        CardJpaEntity e = new CardJpaEntity();
        e.id = c.getId();
        e.cardAccountId = c.getCardAccountId();
        e.holderUserId = c.getHolderUserId();
        e.maskedCardNo = c.getMaskedCardNo();
        e.subLimit = c.getSubLimit();
        e.status = c.getStatus();
        e.version = c.getVersion();
        return e;
    }

    public Card toDomain() {
        return Card.builder()
                .id(id)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .maskedCardNo(maskedCardNo)
                .subLimit(subLimit)
                .status(status)
                .version(version)
                .build();
    }

    public Long getId() {
        return id;
    }
}
