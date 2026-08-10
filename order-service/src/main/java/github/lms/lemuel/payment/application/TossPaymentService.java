package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 토스페이먼츠 결제 확인 서비스
 * Flow: Toss API 확인 → READY 결제 생성 → AUTHORIZED → CAPTURED (정산 포함)
 *
 * 복원력:
 *   - PG 호출은 {@link TossConfirmApiClient}(별도 빈)이 담당 — CircuitBreaker + Retry (Resilience4j)
 *   - <b>별도 빈이어야</b> 스프링 AOP 프록시를 통과한다. 예전처럼 같은 클래스 안에서 자기호출하면
 *     어드바이스가 걸리지 않아 재시도·서킷이 조용히 무력화된다
 *     (회귀 차단: {@code scripts/harness/test/aop-proxy-gate.test.mjs}).
 *   - RestTemplate connect/read timeout 설정으로 쓰레드 고갈 방지
 *   - 4xx (Toss 비즈니스 오류) 는 서킷 판정·재시도 모두에서 제외
 */
@Service
@Transactional
public class TossPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentService.class);

    private final TossConfirmApiClient tossConfirmApiClient;
    private final CreatePaymentPort createPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final CapturePaymentPort capturePaymentPort;

    public TossPaymentService(TossConfirmApiClient tossConfirmApiClient,
                              CreatePaymentPort createPaymentPort,
                              SavePaymentPort savePaymentPort,
                              CapturePaymentPort capturePaymentPort) {
        this.tossConfirmApiClient = tossConfirmApiClient;
        this.createPaymentPort = createPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
    }

    /**
     * 토스페이먼츠 최종 결제 승인
     * 1. Toss API 확인 (paymentKey 검증) — 서킷·재시도 보호
     * 2. 결제 READY 생성
     * 3. authorize(paymentKey) → AUTHORIZED 저장
     * 4. capture() → CAPTURED + 주문 PAID + 정산 자동 생성
     */
    public PaymentDomain confirmTossPayment(Long dbOrderId, String paymentKey, String tossOrderId, Long amount) {
        log.info("토스 결제 확인 시작: dbOrderId={}, tossOrderId={}, amount={}", dbOrderId, tossOrderId, amount);

        tossConfirmApiClient.confirm(paymentKey, tossOrderId, amount);

        PaymentDomain payment = createPaymentPort.createPayment(
                new CreatePaymentCommand(dbOrderId, "TOSS_PAYMENTS")
        );

        // pgTransactionId 는 "PROVIDER:txn" prefix 규칙을 따라야 PgRouter.resolveByTransactionId 가
        // capture/refund 시 올바른 PG 로 라우팅한다. raw paymentKey 를 그대로 저장하면 prefix 미인식 →
        // MOCK 폴백 → "어댑터 없음: provider=MOCK" 로 capture 가 죽는다. TOSS: prefix 를 붙인다.
        payment.authorize(PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + paymentKey);
        savePaymentPort.save(payment);

        PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());

        log.info("토스 결제 완료: paymentId={}", captured.getId());
        return captured;
    }

    /**
     * 토스페이먼츠 장바구니 일괄 결제 확인
     */
    public List<PaymentDomain> confirmTossCartPayment(List<Long> orderIds, String paymentKey,
                                                      String tossOrderId, Long totalAmount) {
        log.info("토스 장바구니 결제 확인 시작: orderIds={}, totalAmount={}", orderIds, totalAmount);

        tossConfirmApiClient.confirm(paymentKey, tossOrderId, totalAmount);

        List<PaymentDomain> results = new ArrayList<>();
        for (Long orderId : orderIds) {
            PaymentDomain payment = createPaymentPort.createPayment(
                    new CreatePaymentCommand(orderId, "TOSS_PAYMENTS")
            );
            // pgTransactionId 는 "PROVIDER:txn" prefix 규칙을 따라야 PgRouter.resolveByTransactionId 가
            // capture/refund 시 올바른 PG 로 라우팅한다. raw paymentKey 를 그대로 저장하면 prefix 미인식 →
            // MOCK 폴백 → "어댑터 없음: provider=MOCK" 로 capture 가 죽는다. TOSS: prefix 를 붙인다.
            payment.authorize(PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + paymentKey);
            savePaymentPort.save(payment);

            PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());
            results.add(captured);
            log.info("장바구니 항목 결제 완료: orderId={}, paymentId={}", orderId, captured.getId());
        }

        log.info("토스 장바구니 결제 전체 완료: {}건", results.size());
        return results;
    }
}
