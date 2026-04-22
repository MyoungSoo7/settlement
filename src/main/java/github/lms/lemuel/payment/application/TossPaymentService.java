package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 토스페이먼츠 결제 확인 서비스
 * Flow: Toss API 확인 → READY 결제 생성 → AUTHORIZED → CAPTURED (정산 포함)
 *
 * 복원력:
 *   - callTossConfirmApi 에 CircuitBreaker + Retry (Resilience4j)
 *   - RestTemplate connect/read timeout 설정으로 쓰레드 고갈 방지
 *   - 4xx (Toss 비즈니스 오류) 는 서킷 판정·재시도 모두에서 제외
 */
@Service
@Transactional
public class TossPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentService.class);
    private static final String PG_INSTANCE = "tossPg";

    @Value("${toss.secret-key}")
    private String secretKey;

    @Value("${toss.api-url}")
    private String tossApiUrl;

    private final RestTemplate restTemplate;
    private final CreatePaymentPort createPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final CapturePaymentPort capturePaymentPort;

    public TossPaymentService(RestTemplateBuilder restTemplateBuilder,
                              CreatePaymentPort createPaymentPort,
                              SavePaymentPort savePaymentPort,
                              CapturePaymentPort capturePaymentPort) {
        // connect/read timeout 으로 쓰레드 풀 고갈 방지 — 상위는 Resilience4j 가 담당
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
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

        callTossConfirmApi(paymentKey, tossOrderId, amount);

        PaymentDomain payment = createPaymentPort.createPayment(
                new CreatePaymentCommand(dbOrderId, "TOSS_PAYMENTS")
        );

        payment.authorize(paymentKey);
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

        callTossConfirmApi(paymentKey, tossOrderId, totalAmount);

        List<PaymentDomain> results = new ArrayList<>();
        for (Long orderId : orderIds) {
            PaymentDomain payment = createPaymentPort.createPayment(
                    new CreatePaymentCommand(orderId, "TOSS_PAYMENTS")
            );
            payment.authorize(paymentKey);
            savePaymentPort.save(payment);

            PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());
            results.add(captured);
            log.info("장바구니 항목 결제 완료: orderId={}, paymentId={}", orderId, captured.getId());
        }

        log.info("토스 장바구니 결제 전체 완료: {}건", results.size());
        return results;
    }

    /**
     * Toss Payments 결제 확인 API 호출 — Resilience4j 로 보호.
     *
     * - Retry: 네트워크 I/O / 5xx 오류는 exponential backoff 로 최대 3회 재시도
     * - CircuitBreaker: 최근 20건 중 실패 50% 이상이면 30초간 OPEN
     * - Fallback: tossFallback 으로 위임. 운영에서는 Alertmanager 로 페이지.
     * - 4xx (HttpClientErrorException) 는 PG 비즈니스 오류라 서킷/재시도 모두에서 제외하고 즉시 전파.
     */
    @CircuitBreaker(name = PG_INSTANCE, fallbackMethod = "tossFallback")
    @Retry(name = PG_INSTANCE)
    public void callTossConfirmApi(String paymentKey, String tossOrderId, Long amount) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);

        Map<String, Object> body = new HashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", tossOrderId);
        body.put("amount", amount);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(tossApiUrl, entity, Map.class);
            log.info("Toss API 응답: status={}", response.getStatusCode());

            if (!response.getStatusCode().is2xxSuccessful()) {
                String msg = extractTossError(response.getBody());
                throw new IllegalStateException("Toss 결제 확인 실패: " + msg);
            }

        } catch (HttpClientErrorException e) {
            // 4xx — Toss 비즈니스 에러. 재시도·서킷 대상 아님. 그대로 전파.
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.error("Toss API 4xx: status={}, body={}", e.getStatusCode(), responseBody);
            throw new IllegalStateException("Toss 결제 확인 실패 (" + e.getStatusCode() + "): " + responseBody, e);
        }
    }

    /**
     * CircuitBreaker Fallback — 시그니처는 원본 메서드 + Throwable.
     * 서킷 OPEN 또는 재시도 모두 소진 시 호출된다.
     * 운영에서는 여기서 Alertmanager/Slack webhook 알림 발송까지 연계.
     */
    @SuppressWarnings("unused") // Resilience4j 가 리플렉션으로 호출
    public void tossFallback(String paymentKey, String tossOrderId, Long amount, Throwable t) {
        // 4xx 비즈니스 오류는 그대로 상위로 전파
        if (t instanceof IllegalStateException ise) {
            throw ise;
        }
        log.error("Toss PG 서킷 오픈 또는 재시도 소진: paymentKey={}, orderId={}, amount={}, cause={}",
                paymentKey, tossOrderId, amount, t.toString());
        throw new IllegalStateException(
                "Toss PG 일시 장애로 결제 확인을 완료할 수 없습니다. 잠시 후 다시 시도해주세요.", t);
    }

    @SuppressWarnings("unchecked")
    private String extractTossError(Map<?, ?> body) {
        if (body == null) return "알 수 없는 오류";
        Object code = body.get("code");
        Object message = body.get("message");
        return code + " - " + message;
    }
}
