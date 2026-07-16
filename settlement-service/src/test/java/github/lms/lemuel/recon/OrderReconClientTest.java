package github.lms.lemuel.recon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ADR 0020 Phase 5 — {@link OrderReconClient} ↔ order {@code /internal/recon/*} 계약 검증.
 *
 * <p>실제 order 를 띄우지 않고 {@link MockRestServiceServer} 로 HTTP 경계를 스텁해, 클라이언트가
 * 올바른 URL·메서드·바디로 요청하고 응답 JSON 필드를 정확히 파싱하는지 확인한다(계약 가드).
 * 재시도 검증은 백오프 0(단일 인자 생성자)으로 지연 없이 확인한다.
 */
class OrderReconClientTest {

    private MockRestServiceServer server;
    private OrderReconClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://order-test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OrderReconClient(builder.build());
    }

    @Test
    void paymentKeysChecksum_parsesCountAmountAndChecksum() {
        server.expect(requestTo("http://order-test/internal/recon/payment-keys-checksum?date=2026-06-17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"count\":3,\"amountSum\":3000.00,\"idChecksum\":\"abc123\"}", APPLICATION_JSON));

        OrderReconClient.PaymentKeyChecksum c = client.paymentKeysChecksum(LocalDate.of(2026, 6, 17));

        assertThat(c.count()).isEqualTo(3L);
        assertThat(c.amountSum()).isEqualByComparingTo("3000.00");
        assertThat(c.idChecksum()).isEqualTo("abc123");
        server.verify();
    }

    @Test
    void paymentKeys_parsesKeyRowsWithAfterIdAndLimit() {
        server.expect(requestTo(
                "http://order-test/internal/recon/payment-keys?date=2026-06-17&afterId=2&limit=1000"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"paymentId\":3,\"amount\":1000.00}]", APPLICATION_JSON));

        List<OrderReconClient.PaymentKeyRow> rows =
                client.paymentKeys(LocalDate.of(2026, 6, 17), 2L, 1000);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).paymentId()).isEqualTo(3L);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1000.00");
        server.verify();
    }

    @Test
    void dailyTotals_requestsCorrectUriAndParsesResponse() {
        server.expect(requestTo("http://order-test/internal/recon/daily-totals?date=2026-06-17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"capturedPayments\":1000.00,\"completedRefunds\":50.00,\"refundedAgainstCaptures\":40.00}",
                        APPLICATION_JSON));

        OrderReconClient.DailyTotals totals = client.dailyTotals(LocalDate.of(2026, 6, 17));

        assertThat(totals.capturedPayments()).isEqualByComparingTo("1000.00");
        assertThat(totals.completedRefunds()).isEqualByComparingTo("50.00");
        assertThat(totals.refundedAgainstCaptures()).isEqualByComparingTo("40.00");
        server.verify();
    }

    @Test
    void periodTotals_parsesAmountsAndCount() {
        server.expect(requestTo("http://order-test/internal/recon/period-totals?from=2026-06-01&to=2026-06-30"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"capturedPayments\":900.00,\"completedRefunds\":10.00,\"paymentCapturedPublishedCount\":12}",
                        APPLICATION_JSON));

        OrderReconClient.PeriodTotals totals =
                client.periodTotals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(totals.capturedPayments()).isEqualByComparingTo("900.00");
        assertThat(totals.completedRefunds()).isEqualByComparingTo("10.00");
        assertThat(totals.paymentCapturedPublishedCount()).isEqualTo(12L);
        server.verify();
    }

    @Test
    void refundsCompletedSum_postsRefundIdsAndParsesAmount() {
        server.expect(requestTo("http://order-test/internal/recon/refunds-completed-sum"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.refundIds.length()").value(2))
                .andExpect(jsonPath("$.refundIds[0]").value(7))
                .andRespond(withSuccess("{\"amount\":150.00}", APPLICATION_JSON));

        BigDecimal sum = client.refundsCompletedSum(List.of(7L, 8L));

        assertThat(sum).isEqualByComparingTo("150.00");
        server.verify();
    }

    @Test
    void refundsCompletedSum_emptyIds_shortCircuitsWithoutHttpCall() {
        // 기대 설정 없음 — HTTP 호출이 발생하면 server.verify() 가 실패한다.
        BigDecimal sum = client.refundsCompletedSum(List.of());

        assertThat(sum).isEqualByComparingTo("0");
        server.verify();
    }

    @Test
    void dailyCounts_parsesCounts() {
        server.expect(requestTo("http://order-test/internal/recon/daily-counts?date=2026-06-17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"capturedCount\":12,\"completedRefundsCount\":3}", APPLICATION_JSON));

        OrderReconClient.DailyCounts counts = client.dailyCounts(LocalDate.of(2026, 6, 17));

        assertThat(counts.capturedCount()).isEqualTo(12L);
        assertThat(counts.completedRefundsCount()).isEqualTo(3L);
        server.verify();
    }

    @Test
    void refundsCompleted_parsesRowListWithParams() {
        server.expect(requestTo(
                "http://order-test/internal/recon/refunds-completed?from=2026-06-01&to=2026-06-30&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"refundId":5,"paymentId":1,"amount":2000.00,"completedDate":"2026-06-15"}]
                        """, APPLICATION_JSON));

        List<OrderReconClient.CompletedRefundRow> rows =
                client.refundsCompleted(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).refundId()).isEqualTo(5L);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("2000.00");
        server.verify();
    }

    @Test
    void refundsCompletedSum_nullAmountResponse_returnsZero() {
        server.expect(requestTo("http://order-test/internal/recon/refunds-completed-sum"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", APPLICATION_JSON));

        BigDecimal sum = client.refundsCompletedSum(List.of(1L));

        assertThat(sum).isEqualByComparingTo("0");
        server.verify();
    }

    @Test
    void capturedPayments_parsesRowList() {
        server.expect(requestTo("http://order-test/internal/recon/captured-payments?date=2026-06-17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"paymentId":1,"pgTransactionId":"pg_1","amount":1000.00,"refundedAmount":0.00,"capturedDate":"2026-06-17"}]
                        """, APPLICATION_JSON));

        List<OrderReconClient.ReconPaymentRow> rows = client.capturedPayments(LocalDate.of(2026, 6, 17));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).paymentId()).isEqualTo(1L);
        assertThat(rows.get(0).pgTransactionId()).isEqualTo("pg_1");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1000.00");
        assertThat(rows.get(0).capturedDate()).isEqualTo(LocalDate.of(2026, 6, 17));
        server.verify();
    }

    @Test
    void dailyTotals_serverError_bothAttemptsFail_isTranslatedToOrderReconUnavailable() {
        // 5xx 는 재시도 대상 — 두 시도(최초+재시도) 모두 500 이면 재시도 소진으로 실패시킨다.
        String uri = "http://order-test/internal/recon/daily-totals?date=2026-06-17";
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET)).andRespond(withServerError());
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET)).andRespond(withServerError());

        assertThatThrownBy(() -> client.dailyTotals(LocalDate.of(2026, 6, 17)))
                .isInstanceOf(OrderReconUnavailableException.class)
                .hasMessageContaining("daily-totals");
        server.verify();
    }

    @Test
    void periodTotals_connectionFailure_bothAttemptsFail_isTranslatedToOrderReconUnavailable() {
        // ResponseCreator 가 IOException 을 던지면 RestClient 는 ResourceAccessException(타임아웃/연결불가
        // 계열)으로 감싼다 — 실제 소켓 타임아웃과 동일 경로. 재시도 대상이므로 두 시도 모두 실패시켜야 한다.
        String uri = "http://order-test/internal/recon/period-totals?from=2026-06-01&to=2026-06-30";
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET))
                .andRespond(request -> { throw new IOException("simulated timeout"); });
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET))
                .andRespond(request -> { throw new IOException("simulated timeout"); });

        assertThatThrownBy(() -> client.periodTotals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .isInstanceOf(OrderReconUnavailableException.class)
                .hasMessageContaining("period-totals");
        server.verify();
    }

    @Test
    void dailyTotals_firstAttemptServerError_thenSecondSucceeds_retriesAndParses() {
        // 첫 시도 5xx → 백오프(0) 후 재시도 → 성공. 일회성 순단이 대사 run 을 실패시키지 않아야 한다.
        String uri = "http://order-test/internal/recon/daily-totals?date=2026-06-17";
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET)).andRespond(withServerError());
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"capturedPayments\":1000.00,\"completedRefunds\":50.00,\"refundedAgainstCaptures\":40.00}",
                APPLICATION_JSON));

        OrderReconClient.DailyTotals totals = client.dailyTotals(LocalDate.of(2026, 6, 17));

        assertThat(totals.capturedPayments()).isEqualByComparingTo("1000.00");
        assertThat(totals.completedRefunds()).isEqualByComparingTo("50.00");
        server.verify();
    }

    @Test
    void dailyTotals_clientError4xx_isNotRetriedAndTranslated() {
        // 4xx 는 요청 자체 문제 — 재시도하지 않고 즉시 실패시킨다. 단 한 번만 호출돼야 한다(server.verify).
        server.expect(requestTo("http://order-test/internal/recon/daily-totals?date=2026-06-17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.dailyTotals(LocalDate.of(2026, 6, 17)))
                .isInstanceOf(OrderReconUnavailableException.class)
                .hasMessageContaining("daily-totals");
        server.verify();
    }
}
