package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.GiftCardTenderPort;
import github.lms.lemuel.payment.application.port.out.PointTenderPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 분할결제 생성 서비스.
 *
 * <p>흐름:
 * <ol>
 *   <li>요청된 tender 들을 sequence 순서로 PaymentTender 도메인으로 변환</li>
 *   <li>외부 PG tender → PgRouter.authorize/capture (각 tender 별 독립 PG 거래)</li>
 *   <li>내부 잔액 tender → 외부 호출 없이 즉시 CAPTURED (실 운영에서는 PointService/GiftCardService 호출)</li>
 *   <li>모든 tender 가 성공하면 Payment.createSplit + 저장 + 주문 PAID 전이 + outbox 이벤트</li>
 *   <li>중간에 실패 시 트랜잭션 롤백 → 이미 처리된 외부 PG 거래는 별도 보상 처리 필요 (Saga, 본 구현은 단순화)</li>
 * </ol>
 */
@Service
@Transactional
public class CreateSplitPaymentService implements CreateSplitPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateSplitPaymentService.class);

    private final PgClientPort pgClientPort;
    private final SavePaymentPort savePaymentPort;
    private final UpdateOrderStatusPort updateOrderStatusPort;
    private final PublishEventPort publishEventPort;
    private final github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    private final PointTenderPort pointTenderPort;
    private final GiftCardTenderPort giftCardTenderPort;

    public CreateSplitPaymentService(PgClientPort pgClientPort,
                                      SavePaymentPort savePaymentPort,
                                      UpdateOrderStatusPort updateOrderStatusPort,
                                      PublishEventPort publishEventPort,
                                      github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort,
                                      PointTenderPort pointTenderPort,
                                      GiftCardTenderPort giftCardTenderPort) {
        this.pgClientPort = pgClientPort;
        this.savePaymentPort = savePaymentPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
        this.pointTenderPort = pointTenderPort;
        this.giftCardTenderPort = giftCardTenderPort;
    }

    @Override
    public PaymentDomain createSplit(Long orderId, List<TenderRequest> tenderRequests, Long actorUserId) {
        if (tenderRequests == null || tenderRequests.size() < 2) {
            throw new PaymentInvariantViolationException("분할결제는 최소 2 개의 지불수단 필요");
        }

        log.info("분할결제 시작: orderId={}, tenders={}", orderId, tenderRequests.size());

        List<PaymentTender> tenders = new ArrayList<>(tenderRequests.size());
        int seq = 1;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (TenderRequest req : tenderRequests) {
            PaymentTender tender = PaymentTender.newTender(req.type(), req.amount(), seq++);
            tenders.add(tender);
            totalAmount = totalAmount.add(req.amount());
        }

        // 가장 큰 tender 의 type 을 paymentMethod 표시값으로 사용 (운영자 가시성)
        String paymentMethod = pickPrimaryMethodLabel(tenderRequests);
        PaymentDomain payment = PaymentDomain.createSplit(orderId, tenders, paymentMethod);

        // 각 tender 처리
        for (PaymentTender tender : tenders) {
            processTender(tender, orderId);
        }

        // 부모 Payment 캡처 + 저장
        payment.authorize("SPLIT-" + orderId);  // 합산 식별자 (실 운영은 별도 정책)
        payment.capture();
        PaymentDomain saved = savePaymentPort.save(payment);

        // 포인트 차감은 저장 이후다 — 원장 멱등 키가 tenderId 라, 식별자가 확정되기 전에는
        // 같은 결제를 두 번 차감했는지 구분할 방법이 없다. 같은 트랜잭션이므로 실패하면 함께 롤백된다.
        deductInternalTenders(saved, actorUserId);

        updateOrderStatusPort.updateOrderStatus(saved.getOrderId(), "PAID");
        publishEventPort.publishPaymentCaptured(saved.getId(), saved.getOrderId(), saved.getAmount(),
                saved.getCapturedAt(),
                saved.getPaymentMethod(),
                saved.getPgTransactionId(),
                loadSellerSettlementMetaPort.findByPaymentId(saved.getId()).orElse(null));

        log.info("분할결제 완료: paymentId={}, totalAmount={}, tenders={}",
                saved.getId(), saved.getAmount(), saved.getTenders().size());
        return saved;
    }

    private void processTender(PaymentTender tender, Long orderId) {
        if (tender.getType().usesExternalPg()) {
            // 외부 PG 호출 — PgRouter 가 자동으로 적합한 PG 어댑터 선택
            String pgTxnId = pgClientPort.authorize(orderId, tender.getAmount(), tender.getType().name());
            tender.authorize(pgTxnId);
            pgClientPort.capture(pgTxnId, tender.getAmount());
            tender.capture();
            log.debug("외부 PG tender 처리: type={}, amount={}, pgTxn={}",
                    tender.getType(), tender.getAmount(), pgTxnId);
        } else {
            // 내부 잔액 tender — 외부 호출은 없다. 실제 원장 차감은 저장 후
            // deductInternalTenders 에서 한다(원장 자연키가 tenderId 인데, 저장 전에는
            // 그 식별자가 없기 때문).
            tender.authorize(null);
            tender.capture();
            log.debug("내부 잔액 tender 처리: type={}, amount={}", tender.getType(), tender.getAmount());
        }
    }

    /**
     * 내부 잔액 tender(POINT·GIFT_CARD)를 각자의 원장에서 실제로 차감한다.
     *
     * <p>여기가 "장부 없는 결제 수단"을 닫는 지점이다. 잔액이 모자라면 예외가 올라와 결제 전체가
     * 롤백된다 — 검증 없이 통과시키던 이전 동작보다 결제 실패가 옳다.
     *
     * <p>차감이 저장 이후인 이유: 두 원장 모두 멱등 키가 tenderId 인데, 저장 전에는 그 식별자가
     * 없어 같은 결제를 두 번 차감했는지 구분할 수 없다.
     */
    private void deductInternalTenders(PaymentDomain saved, Long actorUserId) {
        for (PaymentTender tender : saved.getTenders()) {
            if (tender.getType().usesExternalPg()) {
                continue;
            }
            if (actorUserId == null) {
                // 주체를 모른 채 남의 잔액을 건드릴 수는 없다.
                throw new PaymentInvariantViolationException(
                        "내부 잔액 결제에는 인증 주체가 필요합니다: paymentId=" + saved.getId());
            }
            if (tender.getType() == TenderType.POINT) {
                pointTenderPort.use(actorUserId, tender.getAmount(), tender.getId());
            } else if (tender.getType() == TenderType.GIFT_CARD) {
                giftCardTenderPort.use(actorUserId, tender.getAmount(), tender.getId());
            }
        }
    }

    private String pickPrimaryMethodLabel(List<TenderRequest> reqs) {
        TenderType primary = reqs.stream()
                .max((a, b) -> a.amount().compareTo(b.amount()))
                .map(TenderRequest::type)
                .orElse(TenderType.CARD);
        return "SPLIT:" + primary.name();
    }
}
