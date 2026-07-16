package github.lms.lemuel.notification.application

/** Per-channel outcome. Sealed so callers exhaustively handle success vs failure. */
sealed interface ChannelResult {
    val channel: String

    data class Success(override val channel: String, val attempts: Int) : ChannelResult
    data class Failure(override val channel: String, val attempts: Int, val error: String) : ChannelResult
}

/**
 * Aggregate result of fanning one notification out to all enabled channels.
 * `results` is defensively copied at construction, so a caller-held mutable
 * list can never mutate a published result.
 *
 * @param deduped true when the notification was skipped entirely as a duplicate.
 */
class DispatchResult(
    val deduped: Boolean,
    results: List<ChannelResult>,
) {
    val results: List<ChannelResult> = results.toList()

    val anySucceeded: Boolean get() = results.any { it is ChannelResult.Success }
    val allSucceeded: Boolean get() = results.isNotEmpty() && results.all { it is ChannelResult.Success }

    companion object {
        fun skipped() = DispatchResult(deduped = true, results = emptyList())
    }
}
