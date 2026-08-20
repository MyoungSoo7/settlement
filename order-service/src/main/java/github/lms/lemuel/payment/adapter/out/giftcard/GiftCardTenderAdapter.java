package github.lms.lemuel.payment.adapter.out.giftcard;

import github.lms.lemuel.giftcard.application.port.in.RestoreGiftCardUseCase;
import github.lms.lemuel.giftcard.application.port.in.UseGiftCardUseCase;
import github.lms.lemuel.payment.application.port.out.GiftCardTenderPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 결제 ↔ 기프트카드 원장 연결 어댑터.
 *
 * <p>포인트 어댑터와 같은 자리, 같은 규약이다. 두 도메인이 같은 서비스·같은 DB 안에 있으므로
 * 호출은 같은 트랜잭션에서 원자적으로 일어난다.
 */
@Component
public class GiftCardTenderAdapter implements GiftCardTenderPort {

    /** 원장 자연키의 일부 — 값이 바뀌면 멱등이 깨진다. 상수로 고정한다. */
    private static final String USE_REFERENCE_TYPE = "PAYMENT_TENDER";
    private static final String REFUND_REFERENCE_TYPE = "PAYMENT_TENDER_REFUND";

    private final UseGiftCardUseCase useGiftCardUseCase;
    private final RestoreGiftCardUseCase restoreGiftCardUseCase;

    public GiftCardTenderAdapter(UseGiftCardUseCase useGiftCardUseCase,
                                 RestoreGiftCardUseCase restoreGiftCardUseCase) {
        this.useGiftCardUseCase = useGiftCardUseCase;
        this.restoreGiftCardUseCase = restoreGiftCardUseCase;
    }

    @Override
    public void use(Long userId, BigDecimal amount, Long tenderId) {
        useGiftCardUseCase.use(new UseGiftCardUseCase.UseGiftCardCommand(
                userId, amount, USE_REFERENCE_TYPE, String.valueOf(tenderId), "user:" + userId));
    }

    @Override
    public void restore(BigDecimal amount, Long tenderId, String refundReference) {
        restoreGiftCardUseCase.restore(new RestoreGiftCardUseCase.RestoreGiftCardCommand(
                amount,
                USE_REFERENCE_TYPE, String.valueOf(tenderId),
                REFUND_REFERENCE_TYPE, refundReference,
                "payment-refund"));
    }
}
