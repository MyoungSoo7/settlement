package github.lms.lemuel.service;

import github.lms.lemuel.domain.Order;
import github.lms.lemuel.domain.Payment;
import github.lms.lemuel.domain.Settlement;
import github.lms.lemuel.repository.OrderRepository;
import github.lms.lemuel.repository.PaymentRepository;
import github.lms.lemuel.repository.SettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 환불 처리 서비스
 *
 * 환불 시나리오 3가지:
 * 1. 전체 환불 (Full Refund)
 * 2. 부분 환불 (Partial Refund)
 * 3. 결제 실패 환불 (Failed Payment Refund)
 */
@Service
public class RefundService {

    private static final Logger logger = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final SettlementRepository settlementRepository;

    public RefundService(PaymentRepository paymentRepository, OrderRepository orderRepository, SettlementRepository settlementRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.settlementRepository = settlementRepository;
    }

    /**
     * 시나리오 1: 전체 환불 (Full Refund)
     *
     * 규칙:
     * - Payment.status: CAPTURED -> REFUNDED
     * - Order.status: PAID -> REFUNDED
     * - Settlement.status: PENDING/CONFIRMED -> CANCELED
     * - 환불 금액 = 결제 금액 전체
     *
     * @param paymentId 결제 ID
     * @return 환불 처리된 결제 객체
     */
    @Transactional
    public Payment processFullRefund(Long paymentId) {
        logger.info("전체 환불 처리 시작: paymentId={}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));

        // 유효성 검사: CAPTURED 상태만 환불 가능
        if (payment.getStatus() != Payment.PaymentStatus.CAPTURED) {
            throw new RuntimeException("Only CAPTURED payments can be refunded. Current status: " + payment.getStatus());
        }

        // 1. Payment 상태 변경: CAPTURED -> REFUNDED
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        // 2. Order 상태 변경: PAID -> REFUNDED
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + payment.getOrderId()));
        order.setStatus(Order.OrderStatus.REFUNDED);
        orderRepository.save(order);

        // 3. Settlement 상태 변경: PENDING/CONFIRMED -> CANCELED
        settlementRepository.findByPaymentId(paymentId).ifPresent(settlement -> {
            settlement.setStatus(Settlement.SettlementStatus.CANCELED);
            settlementRepository.save(settlement);
            logger.info("정산 취소 처리: settlementId={}", settlement.getId());
        });

        logger.info("전체 환불 처리 완료: paymentId={}, amount={}", paymentId, payment.getAmount());
        return payment;
    }

    /**
     * 시나리오 2: 부분 환불 (Partial Refund)
     *
     * 규칙:
     * - 원본 Payment는 CAPTURED 유지 (amount는 환불 후 금액으로 조정)
     * - 새로운 Payment 레코드 생성 (음수 금액, REFUNDED 상태)
     * - Order.status: PAID 유지 (부분 환불은 주문 자체는 유효)
     * - Settlement: 환불 금액만큼 차감된 새 Settlement 생성 또는 금액 조정
     *
     * 주의: 이 구현은 간단한 버전이며, 실제로는 환불 이력 테이블을 별도로 관리하는 것이 권장됨
     *
     * @param paymentId 결제 ID
     * @param refundAmount 환불 금액
     * @return 환불 처리 결과
     */
    @Transactional
    public Payment processPartialRefund(Long paymentId, BigDecimal refundAmount) {
        logger.info("부분 환불 처리 시작: paymentId={}, refundAmount={}", paymentId, refundAmount);

        Payment originalPayment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));

        // 유효성 검사
        if (originalPayment.getStatus() != Payment.PaymentStatus.CAPTURED) {
            throw new RuntimeException("Only CAPTURED payments can be refunded");
        }

        if (refundAmount.compareTo(originalPayment.getAmount()) > 0) {
            throw new RuntimeException("Refund amount cannot exceed payment amount");
        }

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Refund amount must be positive");
        }

        // 전체 환불과 동일한 금액이면 전체 환불로 처리
        if (refundAmount.compareTo(originalPayment.getAmount()) == 0) {
            return processFullRefund(paymentId);
        }

        // 1. 환불 금액에 해당하는 음수 Payment 레코드 생성
        Payment refundPayment = new Payment();
        refundPayment.setOrderId(originalPayment.getOrderId());
        refundPayment.setAmount(refundAmount.negate()); // 음수로 저장
        refundPayment.setStatus(Payment.PaymentStatus.REFUNDED);
        refundPayment.setPaymentMethod(originalPayment.getPaymentMethod());
        refundPayment.setPgTransactionId("REFUND-" + originalPayment.getPgTransactionId());
        paymentRepository.save(refundPayment);

        // 2. Order는 PAID 상태 유지 (부분 환불)

        // 3. Settlement 조정: 환불 금액만큼 차감
        settlementRepository.findByPaymentId(paymentId).ifPresent(settlement -> {
            BigDecimal adjustedAmount = settlement.getAmount().subtract(refundAmount);
            settlement.setAmount(adjustedAmount);
            settlementRepository.save(settlement);
            logger.info("정산 금액 조정: settlementId={}, newAmount={}", settlement.getId(), adjustedAmount);
        });

        logger.info("부분 환불 처리 완료: paymentId={}, refundAmount={}", paymentId, refundAmount);
        return refundPayment;
    }

    /**
     * 시나리오 3: 결제 실패 환불 (Failed Payment Refund)
     *
     * 규칙:
     * - 결제 승인(AUTHORIZED) 후 매입(CAPTURE) 실패 시 발생
     * - Payment.status: AUTHORIZED -> CANCELED
     * - Order.status: CREATED 유지 (결제가 완료되지 않음)
     * - Settlement: 생성되지 않음 (매입되지 않았으므로)
     *
     * @param paymentId 결제 ID
     * @return 취소 처리된 결제 객체
     */
    @Transactional
    public Payment processFailedPaymentRefund(Long paymentId) {
        logger.info("결제 실패 환불(취소) 처리 시작: paymentId={}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));

        // 유효성 검사: AUTHORIZED 또는 FAILED 상태만 취소 가능
        if (payment.getStatus() != Payment.PaymentStatus.AUTHORIZED
                && payment.getStatus() != Payment.PaymentStatus.FAILED) {
            throw new RuntimeException("Only AUTHORIZED or FAILED payments can be canceled. Current status: " + payment.getStatus());
        }

        // 1. Payment 상태 변경: AUTHORIZED/FAILED -> CANCELED
        payment.setStatus(Payment.PaymentStatus.CANCELED);
        paymentRepository.save(payment);

        // 2. Order는 CREATED 상태 유지 (재결제 가능)
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + payment.getOrderId()));

        if (order.getStatus() != Order.OrderStatus.CREATED) {
            logger.warn("Order 상태가 CREATED가 아님: orderId={}, status={}", order.getId(), order.getStatus());
        }

        // 3. Settlement은 존재하지 않음 (CAPTURED 되지 않았으므로)
        settlementRepository.findByPaymentId(paymentId).ifPresent(settlement -> {
            logger.warn("AUTHORIZED 상태인데 Settlement이 존재함 (비정상): settlementId={}", settlement.getId());
        });

        logger.info("결제 실패 환불(취소) 처리 완료: paymentId={}", paymentId);
        return payment;
    }
}
