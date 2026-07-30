package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.SourceRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * 실 원천 응답 → [github.lms.lemuel.reconciliation.domain.ReconRecord] 매핑 계약.
 *
 * 두 소스가 **같은 금액 정의(캡처-환불)와 같은 상태 정규화(PAID/REFUNDED)** 를 쓰는지가 핵심이다.
 * 하나라도 어긋나면 대사가 매일 전 건 불일치를 뱉는다.
 */
class HttpSourcesTest {

    private val date = LocalDate.of(2026, 7, 30)
    private val period = ReconPeriod.day(date)

    private fun settlementUri(after: Long) =
        "http://settlement/internal/recon/settlements?date=2026-07-30" +
            "&afterPaymentId=$after&limit=${SettlementHttpSource.PAGE_SIZE}"

    @Test
    fun `settlement 소스는 정산 행을 EXPECTED 레코드로 매핑한다`() = runTest {
        val builder = RestClient.builder().baseUrl("http://settlement")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settlementUri(0)))
            .andRespond(
                withSuccess(
                    """[{"paymentId":1314,"netPaidAmount":2990000.00,"status":"PAID"}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val source = SettlementHttpSource(builder.build())
        val records = source.fetch(period)

        assertEquals(SourceRole.EXPECTED, source.role)
        assertEquals(1, records.size)
        assertEquals("1314", records[0].businessKey)
        assertEquals(2_990_000L, records[0].amountKrw)
        assertEquals("PAID", records[0].status)
        server.verify()
    }

    /**
     * 절단은 침묵하면 안 된다 — 한 페이지가 가득 차면 커서를 밀어 다음 페이지를 마저 읽는다.
     * 이걸 안 하면 상한 초과분이 통째로 빠지고, 상대편은 전건을 돌려주므로 그 차이가 전부
     * EXTRA 로 보고된다.
     */
    @Test
    fun `settlement 소스는 페이지가 가득 차면 소진할 때까지 커서를 민다`() = runTest {
        val page = SettlementHttpSource.PAGE_SIZE
        val firstPage = (1..page).joinToString(",") {
            """{"paymentId":$it,"netPaidAmount":1000.00,"status":"PAID"}"""
        }
        val builder = RestClient.builder().baseUrl("http://settlement")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(settlementUri(0)))
            .andRespond(withSuccess("[$firstPage]", MediaType.APPLICATION_JSON))
        server.expect(requestTo(settlementUri(page.toLong())))
            .andRespond(
                withSuccess(
                    """[{"paymentId":${page + 1},"netPaidAmount":2000.00,"status":"REFUNDED"}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val records = SettlementHttpSource(builder.build()).fetch(period)

        assertEquals(page + 1, records.size)
        assertEquals("${page + 1}", records.last().businessKey)
        assertEquals("REFUNDED", records.last().status)
        server.verify()
    }

    @Test
    fun `payment 소스는 환불액을 빼고 상태를 REFUNDED 로 정규화한다`() = runTest {
        val builder = RestClient.builder().baseUrl("http://order")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://order/internal/recon/captured-payments?date=2026-07-30"))
            .andRespond(
                withSuccess(
                    """[
                        {"paymentId":1,"pgTransactionId":"NICE:a","amount":10000.00,"refundedAmount":0.00},
                        {"paymentId":2,"pgTransactionId":"NICE:b","amount":25000.00,"refundedAmount":5000.00}
                    ]""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val source = PaymentHttpSource(builder.build())
        val records = source.fetch(period)

        assertEquals(SourceRole.ACTUAL, source.role)
        assertEquals(2, records.size)
        assertEquals(10_000L, records[0].amountKrw)
        assertEquals("PAID", records[0].status)
        assertEquals(20_000L, records[1].amountKrw)   // 25,000 - 5,000
        assertEquals("REFUNDED", records[1].status)
        server.verify()
    }
}
