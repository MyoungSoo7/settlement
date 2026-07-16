package github.lms.lemuel.reconciliation.domain

/**
 * Outcome of one reconciliation run.
 *
 * `matchedCount` counts business keys that reconciled cleanly (MATCHED is
 * intentionally omitted from `discrepancies` — we only surface problems).
 * `byType` is a convenience aggregation for summaries/alerting.
 */
data class ReconciliationReport(
    val expectedCount: Int,
    val actualCount: Int,
    val matchedCount: Int,
    val discrepancies: List<Discrepancy>,
    val toleranceKrw: Long,
) {
    val discrepancyCount: Int get() = discrepancies.size

    val clean: Boolean get() = discrepancies.isEmpty()

    /** Count of discrepancies grouped by type — every enum value present, defaulting to 0. */
    val byType: Map<DiscrepancyType, Int>
        get() = DiscrepancyType.entries.associateWith { t ->
            discrepancies.count { it.type == t }
        }

    /** One-line human summary for logs / notifications. */
    fun summaryLine(): String =
        "recon: expected=$expectedCount actual=$actualCount matched=$matchedCount " +
            "discrepancies=$discrepancyCount " +
            byType.entries.joinToString(" ") { (t, c) -> "${t.name}=$c" }
}
