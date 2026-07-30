package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.SourceRole
import github.lms.lemuel.reconciliation.domain.ReconRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 실 HTTP 대사 소스.
 *
 * 2026-07-30 이전에는 이 파일이 `TODO … return emptyList()` 스켈레톤이었고 빈으로 등록되지도
 * 않았다. 그래서 프로덕션 대사 배치가 번들 샘플 데이터로 돌며 매일 같은 가짜 결과
 * (expected=5 actual=5 discrepancies=4)를 WARN 으로 뱉었다. 여기서 실제 원천을 붙인다.
 *
 * 양측 모두 상대 서비스의 `/internal/recon` 내부 API 를 호출한다 — 각자 자기 DB 만 읽고 숫자를 HTTP 로
 * 주고받는 구조라, 대사를 위해 cross-DB 연결을 만들지 않는다.
 *
 * `fetch` 는 suspend 지만 RestClient 는 블로킹이므로 [Dispatchers.IO] 로 넘긴다.
 * 그래야 여러 소스를 동시에 당기는 코루틴 병렬성이 그대로 유지된다.
 */
private val log = LoggerFactory.getLogger("github.lms.lemuel.reconciliation.adapter.out.source.HttpSources")

/** 대사 대상 기간의 각 날짜를 순회한다(일 단위 API 를 기간으로 확장). */
private fun ReconPeriod.days(): List<LocalDate> = generateSequence(from) { d ->
    if (d < to) d.plusDays(1) else null
}.toList()

private fun BigDecimal?.toKrw(): Long = (this ?: BigDecimal.ZERO).setScale(0, java.math.RoundingMode.HALF_UP).toLong()

/**
 * EXPECTED — settlement 관점: "이 날 캡처분으로 우리가 잡은 정산".
 * `GET {base}/internal/recon/settlements?date=YYYY-MM-DD`
 */
class SettlementHttpSource(
    private val client: RestClient,
) : ReconciliationSource {
    override val name = "settlement-http"
    override val role = SourceRole.EXPECTED

    private data class Row(val paymentId: Long?, val netPaidAmount: BigDecimal?, val status: String?)

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> = withContext(Dispatchers.IO) {
        period.days().flatMap { date -> fetchDay(date) }
    }

    /**
     * 커서 페이지네이션으로 하루치를 **소진할 때까지** 읽는다.
     *
     * 단일 요청으로 자르면 상한을 넘는 날에 초과분이 조용히 빠지고, 상대편(order)은 전건을
     * 돌려주므로 그 차이가 전부 EXTRA 로 보고된다 — 대사가 없애야 할 거짓 불일치를 대사가
     * 만들어내는 셈이다.
     */
    private fun fetchDay(date: LocalDate): List<ReconRecord> {
        val out = mutableListOf<ReconRecord>()
        var after = 0L
        while (true) {
            val rows = client.get()
                .uri {
                    it.path("/internal/recon/settlements")
                        .queryParam("date", date)
                        .queryParam("afterPaymentId", after)
                        .queryParam("limit", PAGE_SIZE)
                        .build()
                }
                .retrieve()
                .body(Array<Row>::class.java) ?: emptyArray()
            if (rows.isEmpty()) break
            rows.forEach { row ->
                row.paymentId?.let {
                    out += ReconRecord(it.toString(), row.netPaidAmount.toKrw(), row.status ?: "UNKNOWN")
                }
            }
            after = rows.mapNotNull { it.paymentId }.maxOrNull() ?: break
            if (rows.size < PAGE_SIZE) break
        }
        log.debug("settlement-http: {} rows for {}", out.size, date)
        return out
    }

    companion object {
        /** 서버 상한(2000)보다 작게 잡아 한 페이지가 잘리지 않게 한다. */
        const val PAGE_SIZE = 1000
    }
}

/**
 * ACTUAL — 결제/PG 관점: "이 날 실제로 캡처된 결제(PG 거래 보유분)".
 * `GET {base}/internal/recon/captured-payments?date=YYYY-MM-DD`
 *
 * 금액은 상대편과 같은 정의(`amount - refundedAmount`)로 맞추고, 상태도 환불 반영 여부만
 * PAID/REFUNDED 로 정규화한다.
 */
class PaymentHttpSource(
    private val client: RestClient,
) : ReconciliationSource {
    override val name = "payment-http"
    override val role = SourceRole.ACTUAL

    private data class Row(
        val paymentId: Long?,
        val pgTransactionId: String?,
        val amount: BigDecimal?,
        val refundedAmount: BigDecimal?,
    )

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> = withContext(Dispatchers.IO) {
        period.days().flatMap { date ->
            val rows = client.get()
                .uri { it.path("/internal/recon/captured-payments").queryParam("date", date).build() }
                .retrieve()
                .body(Array<Row>::class.java) ?: emptyArray()
            log.debug("payment-http: {} rows for {}", rows.size, date)
            rows.mapNotNull { row ->
                row.paymentId?.let {
                    val refunded = row.refundedAmount ?: BigDecimal.ZERO
                    val net = (row.amount ?: BigDecimal.ZERO).subtract(refunded)
                    ReconRecord(
                        it.toString(),
                        net.toKrw(),
                        if (refunded > BigDecimal.ZERO) "REFUNDED" else "PAID",
                    )
                }
            }
        }
    }
}
