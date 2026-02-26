package github.lms.lemuel.payment.application;

import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 토스페이먼츠 결제 확인 서비스
 * Flow: Toss API 확인 → READY 결제 생성 → AUTHORIZED → CAPTURED (정산 포함)
 */
@Service
@Transactional
public class TossPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentService.class);

    @Value("${toss.secret-key}")
    private String secretKey;

    @Value("${toss.api-url}")
    private String tossApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final CreatePaymentPort createPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final CapturePaymentPort capturePaymentPort;

    public TossPaymentService(CreatePaymentPort createPaymentPort,
                              SavePaymentPort savePaymentPort,
                              CapturePaymentPort capturePaymentPort) {
        this.createPaymentPort = createPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
    }

    /**
     * 토스페이먼츠 최종 결제 승인
     * 1. Toss API 확인 (paymentKey 검증)
     * 2. 결제 READY 생성
     * 3. authorize(paymentKey) → AUTHORIZED 저장
     * 4. capture() → CAPTURED + 주문 PAID + 정산 자동 생성
     */
    public PaymentDomain confirmTossPayment(Long dbOrderId, String paymentKey, String tossOrderId, Long amount) {
        log.info("토스 결제 확인 시작: dbOrderId={}, tossOrderId={}, amount={}", dbOrderId, tossOrderId, amount);

        // 1. Toss API로 결제 검증 (실패 시 예외 발생 → 트랜잭션 롤백)
        callTossConfirmApi(paymentKey, tossOrderId, amount);

        // 2. 결제 생성 (READY)
        PaymentDomain payment = createPaymentPort.createPayment(
                new CreatePaymentCommand(dbOrderId, "TOSS_PAYMENTS")
        );

        // 3. 생성된 도메인 객체에 바로 authorize — 불필요한 DB 재조회 제거
        payment.authorize(paymentKey);
        savePaymentPort.save(payment); // AUTHORIZED 상태로 저장 (flush)

        // 4. capture — CapturePaymentUseCase 가 동일 트랜잭션에서 실행되며
        //    위에서 merge 된 AUTHORIZED 엔티티를 first-level cache 에서 조회
        PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());

        log.info("토스 결제 완료: paymentId={}, pgTxId={}", captured.getId(), captured.getPgTransactionId());
        return captured;
    }

    /**
     * Toss Payments 결제 확인 API 호출
     * POST https://api.tosspayments.com/v1/payments/confirm
     * 4xx 응답 시 Toss 에러 메시지를 그대로 예외에 포함
     */
    private void callTossConfirmApi(String paymentKey, String tossOrderId, Long amount) {
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
            // Toss API 4xx — 응답 본문에 상세 오류 코드/메시지 포함
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.error("Toss API 4xx: status={}, body={}", e.getStatusCode(), responseBody);
            throw new IllegalStateException("Toss 결제 확인 실패 (" + e.getStatusCode() + "): " + responseBody, e);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Toss API 호출 오류: {}", e.getMessage(), e);
            throw new IllegalStateException("Toss API 호출 중 오류: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTossError(Map<?, ?> body) {
        if (body == null) return "알 수 없는 오류";
        Object code = body.get("code");
        Object message = body.get("message");
        return code + " - " + message;
    }
}