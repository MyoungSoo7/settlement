package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentTender;
import github.lms.lemuel.payment.domain.TenderType;
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

    public CreateSplitPaymentService(PgClientPort pgClientPort,
                                      SavePaymentPort savePaymentPort,
                                      UpdateOrderStatusPort updateOrderStatusPort,
                                      PublishEventPort publishEventPort,
                                      github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort loadSellerSettlementMetaPort) {
        this.pgClientPort = pgClientPort;
        this.savePaymentPort = savePaymentPort;
        this.updateOrderStatusPort = updateOrderStatusPort;
        this.publishEventPort = publishEventPort;
        this.loadSellerSettlementMetaPort = loadSellerSettlementMetaPort;
    }

    @Override
    public PaymentDomain createSplit(Long orderId, List<TenderRequest> tenderRequests) {
        if (tenderRequests == null || tenderRequests.size() < 2) {
            throw new IllegalArgumentException("분할결제는 최소 2 개의 지불수단 필요");
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

        updateOrderStatusPort.updateOrderStatus(saved.getOrderId(), "PAID");
        publishEventPort.publishPaymentCaptured(saved.getId(), saved.getOrderId(), saved.getAmount(),
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
            // 내부 잔액 차감 (실 운영: PointService.deduct, GiftCardService.consume)
            // 본 구현은 도메인 모델만 — 실제 잔액 검증/차감 서비스는 별도 도메인의 책임
            tender.authorize(null);
            tender.capture();
            log.debug("내부 잔액 tender 처리: type={}, amount={}", tender.getType(), tender.getAmount());
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
