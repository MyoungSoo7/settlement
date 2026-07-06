package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.RefundOrderPaymentPort;
import github.lms.lemuel.payment.application.port.in.GetPaymentPort;
import github.lms.lemuel.payment.application.port.in.RefundPaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Order 컨텍스트에서 Payment 컨텍스트의 환불을 호출하는 어댑터.
 *
 * <p>{@code payment.adapter.out.persistence.OrderAdapter}(payment→order) 와 대칭 — Payment 의
 * JPA 엔티티·리포지토리를 직접 참조하지 않고 Payment 의 inbound use case({@link GetPaymentPort},
 * {@link RefundPaymentPort})만 호출한다. 두 포트는 각각 별도 빈(GetPaymentUseCase / RefundPaymentUseCase)이라
 * Spring 프록시를 거치므로 RefundPaymentUseCase 의 {@code @Transactional}/{@code @Auditable} 가 정상 적용된다.
 *
 * <p>주입은 {@code @Lazy} 프록시로 받는다 — payment 쪽 {@code OrderAdapter} 가 order 의
 * {@code ChangeOrderStatusUseCase} 를 의존하는 역방향 간선과 만나 생성자 주입 사이클이 되므로
 * (Change→본 어댑터→RefundUseCase→OrderAdapter→Change), 이 간선만 첫 호출 시점으로 지연시켜 끊는다.
 */
@Component
public class OrderPaymentRefundAdapter implements RefundOrderPaymentPort {

    private final GetPaymentPort getPaymentPort;
    private final RefundPaymentPort refundPaymentPort;

    public OrderPaymentRefundAdapter(@Lazy GetPaymentPort getPaymentPort,
                                     @Lazy RefundPaymentPort refundPaymentPort) {
        this.getPaymentPort = getPaymentPort;
        this.refundPaymentPort = refundPaymentPort;
    }

    @Override
    public void refundOrderPayment(Long orderId, BigDecimal amount, String idempotencyKey) {
        PaymentDomain payment = getPaymentPort.getPaymentByOrderId(orderId);
        // amount=null → 전액 환불(payment-{id}-full 기본 멱등 키), amount 지정 → 부분 환불(호출자 멱등 키)
        refundPaymentPort.refundPayment(payment.getId(), amount, idempotencyKey);
    }

    @Override
    public boolean refundOrderPaymentFullyIfPresent(Long orderId) {
        Optional<PaymentDomain> payment = getPaymentPort.findByOrderId(orderId);
        if (payment.isEmpty() || payment.get().getStatus() != PaymentStatus.CAPTURED) {
            return false; // 미결제이거나 환불 가능 상태(CAPTURED)가 아니면 환불 대상 없음
        }
        refundPaymentPort.refundPayment(payment.get().getId(), null, null);
        return true;
    }
}
