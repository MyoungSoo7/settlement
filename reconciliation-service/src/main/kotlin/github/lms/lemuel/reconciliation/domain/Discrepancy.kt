package github.lms.lemuel.reconciliation.domain

/** Coarse category of a discrepancy — used for aggregation & alert routing. */
enum class DiscrepancyType {
    /** Present in `expected` (settlement) but not in `actual` (PG/payout). */
    MISSING,

    /** Present in `actual` but not in `expected` — unexpected money movement. */
    EXTRA,

    /** Present in both, amounts differ beyond the configured tolerance. */
    AMOUNT_MISMATCH,

    /** Present in both, amounts agree, but lifecycle status differs. */
    STATUS_MISMATCH,
}

/**
 * A detected mismatch between the two sides of reconciliation.
 *
 * Sealed hierarchy so the engine (and any consumer) can `when`-match
 * exhaustively — the compiler forces every case to be handled, which is a
 * real safety win for money-moving code.
 */
sealed interface Discrepancy {
    val businessKey: String
    val type: DiscrepancyType

    /** In expected, absent from actual. */
    data class Missing(
        override val businessKey: String,
        val expected: ReconRecord,
    ) : Discrepancy {
        override val type = DiscrepancyType.MISSING
    }

    /** In actual, absent from expected. */
    data class Extra(
        override val businessKey: String,
        val actual: ReconRecord,
    ) : Discrepancy {
        override val type = DiscrepancyType.EXTRA
    }

    /** Both present; amount differs beyond tolerance. */
    data class AmountMismatch(
        override val businessKey: String,
        val expected: ReconRecord,
        val actual: ReconRecord,
        val diffKrw: Long,
    ) : Discrepancy {
        override val type = DiscrepancyType.AMOUNT_MISMATCH
    }

    /** Both present; amount within tolerance but status differs. */
    data class StatusMismatch(
        override val businessKey: String,
        val expected: ReconRecord,
        val actual: ReconRecord,
    ) : Discrepancy {
        override val type = DiscrepancyType.STATUS_MISMATCH
    }
}
