package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardStatus;
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
 * 임직원 카드 영속 엔티티 — 매핑 규약은 {@link CardAccountJpaEntity} 와 동일.
 * 활성 카드 1장 불변식은 partial unique {@code uq_card_active_holder} 가 DB 차원에서 이중 방어한다.
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

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CardJpaEntity() { }

    static CardJpaEntity from(Card card) {
        CardJpaEntity e = new CardJpaEntity();
        e.id = card.getId();
        e.cardAccountId = card.getCardAccountId();
        e.holderUserId = card.getHolderUserId();
        e.maskedCardNo = card.getMaskedCardNo();
        e.subLimit = card.getSubLimit();
        e.status = card.getStatus().name();
        e.version = card.getVersion();
        return e;
    }

    Card toDomain() {
        return Card.builder()
                .id(id)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .maskedCardNo(maskedCardNo)
                .subLimit(subLimit)
                .status(CardStatus.valueOf(status))
                .version(version)
                .build();
    }
}
