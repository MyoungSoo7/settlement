package github.lms.lemuel.reconciliation.domain

/**
 * Outcome of one reconciliation run.
 *
 * `discrepancies` is defensively copied at construction, so a caller-held
 * mutable list can never mutate a published report.
 *
 * `matchedCount` counts business keys that reconciled cleanly (MATCHED is
 * intentionally omitted from `discrepancies` — we only surface problems).
 * `byType` is a convenience aggregation for summaries/alerting.
 */
class ReconciliationReport(
    val expectedCount: Int,
    val actualCount: Int,
    val matchedCount: Int,
    discrepancies: List<Discrepancy>,
    val toleranceKrw: Long,
) {
    val discrepancies: List<Discrepancy> = discrepancies.toList()

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
