package github.lms.lemuel.reconciliation.domain

import kotlin.math.abs

/**
 * Pure reconciliation engine — no framework, no I/O, fully unit-testable.
 *
 * Diffs `expected` (settlement's view) against `actual` (PG/payout/ledger view),
 * keyed by [ReconRecord.businessKey], and classifies every key into exactly one
 * of: matched (omitted), MISSING, EXTRA, AMOUNT_MISMATCH, STATUS_MISMATCH.
 *
 * Ordering of checks per shared key: amount first (money is the priority
 * signal), then status. A key with both an amount and a status mismatch is
 * reported once, as AMOUNT_MISMATCH.
 */
class ReconciliationEngine(
    /** Absolute amount difference (KRW) at or below which amounts are "equal". */
    private val toleranceKrw: Long = 1,
) {
    init {
        require(toleranceKrw >= 0) { "toleranceKrw must be non-negative, was $toleranceKrw" }
    }

    fun reconcile(expected: List<ReconRecord>, actual: List<ReconRecord>): ReconciliationReport {
        val expectedByKey = expected.associateBy { it.businessKey }
        val actualByKey = actual.associateBy { it.businessKey }

        val discrepancies = mutableListOf<Discrepancy>()
        var matched = 0

        // keys only in expected -> MISSING (in expected, not in actual)
        for ((key, exp) in expectedByKey) {
            val act = actualByKey[key]
            if (act == null) {
                discrepancies += Discrepancy.Missing(key, exp)
                continue
            }
            when (val d = classifyShared(key, exp, act)) {
                null -> matched++
                else -> discrepancies += d
            }
        }

        // keys only in actual -> EXTRA (in actual, not expected)
        for ((key, act) in actualByKey) {
            if (!expectedByKey.containsKey(key)) {
                discrepancies += Discrepancy.Extra(key, act)
            }
        }

        return ReconciliationReport(
            expectedCount = expected.size,
            actualCount = actual.size,
            matchedCount = matched,
            discrepancies = discrepancies,
            toleranceKrw = toleranceKrw,
        )
    }

    /** Returns a discrepancy for a key present on both sides, or null if it reconciles. */
    private fun classifyShared(key: String, exp: ReconRecord, act: ReconRecord): Discrepancy? {
        val diff = abs(exp.amountKrw - act.amountKrw)
        return when {
            diff > toleranceKrw ->
                Discrepancy.AmountMismatch(key, exp, act, diffKrw = exp.amountKrw - act.amountKrw)
            exp.status != act.status ->
                Discrepancy.StatusMismatch(key, exp, act)
            else -> null // matched (within tolerance, same status)
        }
    }
}
