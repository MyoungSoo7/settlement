package github.lms.lemuel.reconciliation.adapter.out.source

import github.lms.lemuel.reconciliation.application.ReconPeriod
import github.lms.lemuel.reconciliation.application.ReconciliationSource
import github.lms.lemuel.reconciliation.application.SourceRole
import github.lms.lemuel.reconciliation.domain.ReconRecord
import org.slf4j.LoggerFactory

/**
 * SKELETON HTTP sources — pointed at the settlement / payment services via
 * env-configured base URLs. Not wired as beans for the MVP (no external deps to
 * run the demo); flip them on and implement `fetch` against the real APIs.
 *
 * Left as suspend-friendly skeletons so swapping in a coroutine HTTP client
 * (e.g. Spring WebClient `awaitBody`, or Ktor client) is a drop-in.
 */
class SettlementHttpSource(
    private val baseUrl: String,
) : ReconciliationSource {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "settlement-http"
    override val role = SourceRole.EXPECTED

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> {
        // TODO: GET $baseUrl/internal/settlements?from=..&to=.. -> map to ReconRecord
        log.warn("SettlementHttpSource not implemented (baseUrl={}); returning empty", baseUrl)
        return emptyList()
    }
}

class PaymentHttpSource(
    private val baseUrl: String,
) : ReconciliationSource {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "payment-http"
    override val role = SourceRole.ACTUAL

    override suspend fun fetch(period: ReconPeriod): List<ReconRecord> {
        // TODO: GET $baseUrl/internal/payments?from=..&to=.. -> map to ReconRecord
        log.warn("PaymentHttpSource not implemented (baseUrl={}); returning empty", baseUrl)
        return emptyList()
    }
}
