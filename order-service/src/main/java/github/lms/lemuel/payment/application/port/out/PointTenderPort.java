package github.lms.lemuel.payment.application.port.out;

import java.math.BigDecimal;

/**
 * 결제가 포인트 잔액을 움직일 때 쓰는 아웃바운드 포트.
 *
 * <p>결제 모듈은 포인트 원장의 내부를 알지 않는다 — 로트·엔트리·소비 순서는 point 도메인의 몫이고,
 * 여기서는 "이 tender 만큼 쓰고, 환불되면 되돌린다"만 표현한다.
 *
 * <p>참조 규약: 사용은 {@code PAYMENT_TENDER:{tenderId}}, 환불은
 * {@code PAYMENT_TENDER_REFUND:{idempotencyKey}}. 환불 키가 tender 와 금액을 함께 담고 있어
 * 같은 부분환불을 두 번 반영하는 일을 원장 자연키가 막는다.
 */
public interface PointTenderPort {

    /**
     * 결제 시 포인트 차감. 잔액이 모자라면 예외가 올라와 결제 트랜잭션이 롤백된다 —
     * 검증 없이 통과시키는 것보다 결제를 실패시키는 편이 옳다.
     *
     * @param userId   결제 주체 — JWT 에서 파생된 값이어야 한다(요청 본문을 믿지 마라)
     * @param tenderId 사용 근거가 되는 tender 식별자
     */
    void use(Long userId, BigDecimal amount, Long tenderId);

    /**
     * 환불 시 포인트 복원. 복원 대상 계정은 원장이 알고 있으므로 userId 를 받지 않는다 —
     * 환불은 언제나 <b>낸 사람</b>에게 돌아가야 한다.
     *
     * @param refundReference 환불 멱등 키(tender + 금액)
     */
    void restore(BigDecimal amount, Long tenderId, String refundReference);
}
