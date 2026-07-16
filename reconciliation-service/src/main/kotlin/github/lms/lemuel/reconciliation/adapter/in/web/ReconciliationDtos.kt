package github.lms.lemuel.reconciliation.adapter.`in`.web

import github.lms.lemuel.reconciliation.domain.Discrepancy
import github.lms.lemuel.reconciliation.domain.DiscrepancyType
import github.lms.lemuel.reconciliation.domain.ReconRecord
import github.lms.lemuel.reconciliation.domain.ReconciliationReport

/** Request record — mirrors [ReconRecord] but decoupled from the domain type. */
data class ReconRecordDto(
    val businessKey: String,
    val amountKrw: Long,
    val status: String,
) {
    fun toDomain() = ReconRecord(businessKey, amountKrw, status)
}

/** POST /reconciliation/run body. `toleranceKrw` optional (default 1). */
data class RunRequest(
    val expected: List<ReconRecordDto> = emptyList(),
    val actual: List<ReconRecordDto> = emptyList(),
    val toleranceKrw: Long? = null,
)

/** Flattened discrepancy view for JSON — sealed type projected to a stable shape. */
data class DiscrepancyDto(
    val type: DiscrepancyType,
    val businessKey: String,
    val expectedAmountKrw: Long?,
    val actualAmountKrw: Long?,
    val expectedStatus: String?,
    val actualStatus: String?,
    val diffKrw: Long?,
) {
    companion object {
        // Exhaustive `when` over the sealed hierarchy — compiler-checked coverage.
        fun from(d: Discrepancy): DiscrepancyDto = when (d) {
            is Discrepancy.Missing -> DiscrepancyDto(
                d.type, d.businessKey,
                d.expected.amountKrw, null, d.expected.status, null, null,
            )
            is Discrepancy.Extra -> DiscrepancyDto(
                d.type, d.businessKey,
                null, d.actual.amountKrw, null, d.actual.status, null,
            )
            is Discrepancy.AmountMismatch -> DiscrepancyDto(
                d.type, d.businessKey,
                d.expected.amountKrw, d.actual.amountKrw,
                d.expected.status, d.actual.status, d.diffKrw,
            )
            is Discrepancy.StatusMismatch -> DiscrepancyDto(
                d.type, d.businessKey,
                d.expected.amountKrw, d.actual.amountKrw,
                d.expected.status, d.actual.status, null,
            )
        }
    }
}

/** Response shape for both /run and /demo. */
data class ReportResponse(
    val expectedCount: Int,
    val actualCount: Int,
    val matchedCount: Int,
    val discrepancyCount: Int,
    val toleranceKrw: Long,
    val byType: Map<DiscrepancyType, Int>,
    val discrepancies: List<DiscrepancyDto>,
    val summary: String,
) {
    companion object {
        fun from(report: ReconciliationReport) = ReportResponse(
            expectedCount = report.expectedCount,
            actualCount = report.actualCount,
            matchedCount = report.matchedCount,
            discrepancyCount = report.discrepancyCount,
            toleranceKrw = report.toleranceKrw,
            byType = report.byType,
            discrepancies = report.discrepancies.map { DiscrepancyDto.from(it) },
            summary = report.summaryLine(),
        )
    }
}
