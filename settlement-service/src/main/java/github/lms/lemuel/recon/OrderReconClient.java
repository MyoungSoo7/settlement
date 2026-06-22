package github.lms.lemuel.recon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
// RestClient.Builder 는 settlement 컨텍스트에 autoconfig 빈이 없어 정적 builder() 로 직접 생성한다.

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * order 의 내부 대사 API({@code /internal/recon/*}) 호출 클라이언트 (ADR 0020 Phase 5 self-totals).
 *
 * <p>settlement 의 일일/기간/PG 대사가 order 원천 숫자를 얻기 위해 order DB 를 직접 읽던 것을 대체한다.
 * order 는 자기 DB 만 읽어 자기 합계를 노출하고, settlement 는 자기 settlement_db 숫자와 이 값을 비교한다.
 * → 양측 모두 자기 DB 만 읽으므로 <b>cross-DB 연결 0</b>. order DB 스키마 변경이 settlement 를 깨지 않는다.
 *
 * <p>대사는 배치/관리 작업이라 order 일시 장애 시 해당 대사 run 만 실패하면 되며, 정산 생성·조회 핫패스는
 * 여전히 order 에 무의존(이벤트 기반)이다.
 */
@Component
public class OrderReconClient {

    private final RestClient client;

    @Autowired
    public OrderReconClient(@Value("${app.order-service.base-url:http://localhost:8088}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 테스트용 — 미리 구성된(예: MockRestServiceServer 바인딩) RestClient 주입. */
    OrderReconClient(RestClient client) {
        this.client = client;
    }

    public DailyTotals dailyTotals(LocalDate date) {
        return client.get()
                .uri(b -> b.path("/internal/recon/daily-totals").queryParam("date", date).build())
                .retrieve()
                .body(DailyTotals.class);
    }

    public PeriodTotals periodTotals(LocalDate from, LocalDate to) {
        return client.get()
                .uri(b -> b.path("/internal/recon/period-totals")
                        .queryParam("from", from).queryParam("to", to).build())
                .retrieve()
                .body(PeriodTotals.class);
    }

    public BigDecimal refundsCompletedSum(List<Long> refundIds) {
        if (refundIds == null || refundIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        AmountResponse res = client.post()
                .uri("/internal/recon/refunds-completed-sum")
                .body(new RefundIdsRequest(refundIds))
                .retrieve()
                .body(AmountResponse.class);
        return res != null && res.amount() != null ? res.amount() : BigDecimal.ZERO;
    }

    public List<ReconPaymentRow> capturedPayments(LocalDate date) {
        List<ReconPaymentRow> rows = client.get()
                .uri(b -> b.path("/internal/recon/captured-payments").queryParam("date", date).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return rows != null ? rows : List.of();
    }

    // ── order /internal/recon 응답 계약 (JSON 매칭, shared-common 외 공유 모듈 없음) ──

    public record DailyTotals(BigDecimal capturedPayments, BigDecimal completedRefunds) {
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
}
