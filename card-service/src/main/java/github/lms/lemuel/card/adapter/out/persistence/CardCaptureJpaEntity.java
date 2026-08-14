package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardCapture;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * card_captures 테이블 매핑 (V7).
 *
 * <p>capture_id 가 자연키·멱등 키다. 같은 captureId 를 두 번 저장하면
 * {@code uq_card_capture_id} 제약이 차단한다.
 */
@Entity
@Table(name = "card_captures")
public class CardCaptureJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capture_id", nullable = false, length = 64)
    private String captureId;

    @Column(name = "authorization_id", nullable = false, length = 64)
    private String authorizationId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "holder_user_id", nullable = false)
    private Long holderUserId;

    @Column(name = "captured_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal capturedAmount;

    @Column(name = "merchant_name", length = 200)
    private String merchantName;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected CardCaptureJpaEntity() {
    }

    public static CardCaptureJpaEntity fromDomain(CardCapture c) {
        CardCaptureJpaEntity e = new CardCaptureJpaEntity();
        e.id = c.getId();
        e.captureId = c.getCaptureId();
        e.authorizationId = c.getAuthorizationId();
        e.cardId = c.getCardId();
        e.cardAccountId = c.getCardAccountId();
        e.holderUserId = c.getHolderUserId();
        e.capturedAmount = c.getCapturedAmount();
        e.merchantName = c.getMerchantName();
        e.capturedAt = c.getCapturedAt();
        return e;
    }

    public CardCapture toDomain() {
        return CardCapture.builder()
                .id(id)
                .captureId(captureId)
                .authorizationId(authorizationId)
                .cardId(cardId)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .capturedAmount(capturedAmount)
                .merchantName(merchantName)
                .capturedAt(capturedAt)
                .build();
    }
}
