package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * 결제가 기프트카드 잔액을 움직일 때 쓰는 아웃바운드 포트.
 *
 * <p>{@code PointTenderPort} 와 같은 모양이다. 결제 모듈은 카드가 몇 장인지, 어느 장부터 쓰는지
 * 알지 않는다 — 그건 giftcard 도메인의 몫이다.
 *
 * <p>참조 규약: 사용은 {@code PAYMENT_TENDER:{tenderId}}, 환불은
 * {@code PAYMENT_TENDER_REFUND:{idempotencyKey}}.
 */
public interface GiftCardTenderPort {

    /**
     * 결제 시 상품권 차감. 잔액이 모자라면 예외가 올라와 결제 트랜잭션이 롤백된다.
     *
     * @param userId 결제 주체 — JWT 에서 파생된 값이어야 한다(요청 본문을 믿지 마라)
     */
    void use(Long userId, BigDecimal amount, Long tenderId);

    /**
     * 환불 시 상품권 복원. 복원 대상 카드는 원장이 알고 있으므로 userId 를 받지 않는다 —
     * 환불은 언제나 낸 사람에게 돌아가야 한다.
     */
    void restore(BigDecimal amount, Long tenderId, String refundReference);
}
