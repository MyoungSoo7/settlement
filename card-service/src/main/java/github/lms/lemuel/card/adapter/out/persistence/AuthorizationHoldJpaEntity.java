package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.HoldStatus;
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
 * authorization_holds 테이블 매핑 (V6).
 * {@link CardJpaEntity}·{@link CardAccountJpaEntity} 와 동일 관례:
 * created_at/updated_at 은 DB DEFAULT NOW() 위임(insertable=false/updatable=@PreUpdate).
 */
@Entity
@Table(name = "authorization_holds")
public class AuthorizationHoldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authorization_id", nullable = false, length = 64)
    private String authorizationId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "holder_user_id", nullable = false)
    private Long holderUserId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HoldStatus status;

    @Column(name = "merchant_name", length = 200)
    private String merchantName;

    @Column(length = 4)
    private String mcc;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AuthorizationHoldJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static AuthorizationHoldJpaEntity fromDomain(AuthorizationHold h) {
        AuthorizationHoldJpaEntity e = new AuthorizationHoldJpaEntity();
        e.id = h.getId();
        e.authorizationId = h.getAuthorizationId();
        e.cardId = h.getCardId();
        e.cardAccountId = h.getCardAccountId();
        e.holderUserId = h.getHolderUserId();
        e.amount = h.getAmount();
        e.status = h.getStatus();
        e.merchantName = h.getMerchantName();
        e.mcc = h.getMcc();
        e.authorizedAt = h.getAuthorizedAt();
        e.version = h.getVersion();
        return e;
    }

    public AuthorizationHold toDomain() {
        return AuthorizationHold.builder()
                .id(id)
                .authorizationId(authorizationId)
                .cardId(cardId)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .amount(amount)
                .status(status)
                .merchantName(merchantName)
                .mcc(mcc)
                .authorizedAt(authorizedAt)
                .version(version)
                .build();
    }
}
