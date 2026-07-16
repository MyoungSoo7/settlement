package github.lms.lemuel.recon;

import github.lms.lemuel.common.config.jwt.InternalApiKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
// RestClient.Builder 는 settlement 컨텍스트에 autoconfig 빈이 없어 정적 builder() 로 직접 생성한다.

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * order 의 내부 대사 API({@code /internal/recon/*}) 호출 클라이언트 (ADR 0020 Phase 5 self-totals).
 *
 * <p>settlement 의 일일/기간/PG 대사가 order 원천 숫자를 얻기 위해 order DB 를 직접 읽던 것을 대체한다.
 * order 는 자기 DB 만 읽어 자기 합계를 노출하고, settlement 는 자기 settlement_db 숫자와 이 값을 비교한다.
 * → 양측 모두 자기 DB 만 읽으므로 <b>cross-DB 연결 0</b>. order DB 스키마 변경이 settlement 를 깨지 않는다.
 *
 * <p>대사는 배치/관리 작업이라 order 일시 장애 시 해당 대사 run 만 실패하면 되며, 정산 생성·조회 핫패스는
 * 여전히 order 에 무의존(이벤트 기반)이다. order 일시 순단(타임아웃·연결불가·5xx)은 짧은 백오프 후 1회
 * 재시도해 흔한 일회성 순단이 대사 run 전체를 실패시키지 않게 한다. 4xx(요청 자체 문제)는 재시도 없이
 * 즉시 실패시키고, 재시도까지 소진하면 기존 {@link OrderReconUnavailableException} 으로 번역한다.
 */
@Component
public class OrderReconClient {

    private static final Logger log = LoggerFactory.getLogger(OrderReconClient.class);

    /** 총 시도 횟수 = 최초 1 + 재시도 1. 일시 순단(타임아웃·연결불가·5xx)에 한해 한 번만 더 시도한다. */
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient client;
    /** 재시도 사이의 짧은 백오프. 테스트 생성자는 0 으로 주입해 지연 없이 검증한다. */
    private final Duration retryBackoff;

    @Autowired
    public OrderReconClient(@Value("${app.order-service.base-url:http://localhost:8088}") String baseUrl,
                            @Value("${app.internal.api-key:}") String internalApiKey) {
        // 대사는 배치/관리 작업이라 order 무응답 시 요청 스레드가 무한 hang 하지 않게 connect/read 타임아웃을
        // 명시한다(loan HttpClientConfig 와 동일 전략, 값은 더 짧게). 초과 시 ResourceAccessException →
        // call() 이 OrderReconUnavailableException 으로 번역해 해당 대사 run 만 명시적으로 실패시킨다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        // 내부 API 공유 시크릿 — order 의 InternalApiKeyFilter 가 검증한다. 미설정 시 헤더 생략(개발).
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            builder.defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey);
        }
        this.client = builder.build();
        this.retryBackoff = Duration.ofMillis(200);
    }

    /** 테스트용 — 미리 구성된(예: MockRestServiceServer 바인딩) RestClient 주입(백오프 없음). */
    OrderReconClient(RestClient client) {
        this(client, Duration.ZERO);
    }

    /** 테스트용 — RestClient 와 백오프를 함께 주입(재시도 지연 없이 검증). */
    OrderReconClient(RestClient client, Duration retryBackoff) {
        this.client = client;
        this.retryBackoff = retryBackoff;
    }

    /**
     * order 내부 대사 호출을 감싸 타임아웃·5xx 등 전송 실패를 명시적 신호로 번역하되, 일시 순단은 1회
     * 재시도한다. 타임아웃/연결불가({@link ResourceAccessException})·5xx({@link HttpServerErrorException})
     * 는 짧은 백오프 후 재시도하고(총 {@value #MAX_ATTEMPTS} 회), 4xx({@link HttpClientErrorException}) 는
     * 요청 자체 문제라 재시도 없이 즉시 실패시킨다. 재시도까지 소진하거나 기타 전송 예외면
     * {@link OrderReconUnavailableException} 으로 다시 던져 대사 run 을 무한 hang 대신 명시적 실패로 처리한다.
     */
    private <T> T call(String op, Supplier<T> apiCall) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return apiCall.get();
            } catch (HttpClientErrorException e) {
                // 4xx — 요청 자체 문제라 재시도해도 동일 실패. 즉시 실패시킨다.
                log.error("[OrderRecon] order 내부 대사 API 4xx op={} — 재시도 없이 실패 처리합니다: {}",
                        op, e.toString());
                throw new OrderReconUnavailableException(
                        "order 내부 대사 API 4xx 실패(op=" + op + ")", e);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                // 타임아웃/연결불가/5xx — 일회성 순단 가능성. 마지막 시도가 아니면 짧은 백오프 후 재시도.
                last = e;
                log.warn("[OrderRecon] order 내부 대사 API 일시 실패 op={} 시도={}/{}: {}",
                        op, attempt, MAX_ATTEMPTS, e.toString());
                if (attempt < MAX_ATTEMPTS) {
                    backoff();
                }
            } catch (RestClientException e) {
                // 기타 전송 예외 — 재시도 의미가 불분명하므로 보수적으로 즉시 실패 처리한다.
                log.error("[OrderRecon] order 내부 대사 API 호출 실패 op={} — 대사 run 을 실패 처리합니다: {}",
                        op, e.toString());
                throw new OrderReconUnavailableException(
                        "order 내부 대사 API 호출 실패(op=" + op + ")", e);
            }
        }
        // 재시도까지 모두 소진 — 마지막 일시 실패를 원인으로 명시적 실패시킨다.
        log.error("[OrderRecon] order 내부 대사 API 재시도 소진 op={} — 대사 run 을 실패 처리합니다.", op);
        throw new OrderReconUnavailableException(
                "order 내부 대사 API 호출 실패(op=" + op + ", 재시도 소진)", last);
    }

    /** 재시도 사이 백오프. 인터럽트되면 플래그를 복원하고 즉시 실패로 번역한다. */
    private void backoff() {
        if (retryBackoff.isZero() || retryBackoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryBackoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OrderReconUnavailableException("order 내부 대사 재시도 대기 중 인터럽트", ie);
        }
    }

    public DailyTotals dailyTotals(LocalDate date) {
        return call("daily-totals", () -> client.get()
                .uri(b -> b.path("/internal/recon/daily-totals").queryParam("date", date).build())
                .retrieve()
                .body(DailyTotals.class));
    }

    /** INV-9 건수 대사 축 — 캡처 건수·완료 환불 건수. */
    public DailyCounts dailyCounts(LocalDate date) {
        return call("daily-counts", () -> client.get()
                .uri(b -> b.path("/internal/recon/daily-counts").queryParam("date", date).build())
                .retrieve()
                .body(DailyCounts.class));
    }

    /** INV-8 지연 환불 조정 대사 — 기간 COMPLETED 환불 목록 (완료일 기준). */
    public List<CompletedRefundRow> refundsCompleted(LocalDate from, LocalDate to, int limit) {
        List<CompletedRefundRow> rows = call("refunds-completed", () -> client.get()
                .uri(b -> b.path("/internal/recon/refunds-completed")
                        .queryParam("from", from).queryParam("to", to)
                        .queryParam("limit", limit).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<CompletedRefundRow>>() {}));
        return rows != null ? rows : List.of();
    }

    public PeriodTotals periodTotals(LocalDate from, LocalDate to) {
        return call("period-totals", () -> client.get()
                .uri(b -> b.path("/internal/recon/period-totals")
                        .queryParam("from", from).queryParam("to", to).build())
                .retrieve()
                .body(PeriodTotals.class));
    }

    public BigDecimal refundsCompletedSum(List<Long> refundIds) {
        if (refundIds == null || refundIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        AmountResponse res = call("refunds-completed-sum", () -> client.post()
                .uri("/internal/recon/refunds-completed-sum")
                .body(new RefundIdsRequest(refundIds))
                .retrieve()
                .body(AmountResponse.class));
        return res != null && res.amount() != null ? res.amount() : BigDecimal.ZERO;
    }

    public List<ReconPaymentRow> capturedPayments(LocalDate date) {
        List<ReconPaymentRow> rows = call("captured-payments", () -> client.get()
                .uri(b -> b.path("/internal/recon/captured-payments").queryParam("date", date).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<ReconPaymentRow>>() {}));
        return rows != null ? rows : List.of();
    }

    /** INV-12 프로젝션 diff 1차 스크리닝 — 해당 날짜 캡처 결제 키셋 체크섬(count·금액합·정렬 id md5). */
    public PaymentKeyChecksum paymentKeysChecksum(LocalDate date) {
        PaymentKeyChecksum res = call("payment-keys-checksum", () -> client.get()
                .uri(b -> b.path("/internal/recon/payment-keys-checksum").queryParam("date", date).build())
                .retrieve()
                .body(PaymentKeyChecksum.class));
        // 빈 집합 방어 — 응답 자체가 없으면 count 0·금액 0·빈 체크섬으로 정규화한다.
        return res != null ? res : new PaymentKeyChecksum(0L, BigDecimal.ZERO, "");
    }

    /** INV-12 프로젝션 diff — afterId 초과 결제 키 페이지(id 오름차순, 최대 limit 건). 체크섬 불일치 시에만 호출. */
    public List<PaymentKeyRow> paymentKeys(LocalDate date, long afterId, int limit) {
        List<PaymentKeyRow> rows = call("payment-keys", () -> client.get()
                .uri(b -> b.path("/internal/recon/payment-keys")
                        .queryParam("date", date)
                        .queryParam("afterId", afterId)
                        .queryParam("limit", limit).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<PaymentKeyRow>>() {}));
        return rows != null ? rows : List.of();
    }

    // ── order /internal/recon 응답 계약 (JSON 매칭, shared-common 외 공유 모듈 없음) ──

    public record DailyTotals(BigDecimal capturedPayments, BigDecimal completedRefunds,
                              BigDecimal refundedAgainstCaptures) {
    }

    public record PeriodTotals(BigDecimal capturedPayments, BigDecimal completedRefunds,
                               long paymentCapturedPublishedCount) {
    }

    public record AmountResponse(BigDecimal amount) {
    }

    public record RefundIdsRequest(List<Long> refundIds) {
    }

    public record ReconPaymentRow(Long paymentId, String pgTransactionId, BigDecimal amount,
                                  BigDecimal refundedAmount, LocalDate capturedDate) {
    }

    public record DailyCounts(long capturedCount, long completedRefundsCount) {
    }

    public record CompletedRefundRow(Long refundId, Long paymentId, BigDecimal amount,
                                     LocalDate completedDate) {
    }

    public record PaymentKeyChecksum(long count, BigDecimal amountSum, String idChecksum) {
    }

    public record PaymentKeyRow(Long paymentId, BigDecimal amount) {
    }
}
