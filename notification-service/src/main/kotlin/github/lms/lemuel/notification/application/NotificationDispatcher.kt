package github.lms.lemuel.notification.application

import github.lms.lemuel.notification.domain.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * The heart of the service. Fans a [Notification] out to every enabled channel
 * CONCURRENTLY using coroutines. Each channel is independently wrapped with a
 * per-channel timeout + bounded retry/backoff, so one slow or failing channel
 * never blocks or fails the others; results are aggregated.
 *
 * Idempotency is applied up-front by [dedupe]: a redelivered eventId is skipped.
 */
class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
    private val dedupe: DedupeStore,
    private val perChannelTimeoutMs: Long = 3_000,
    private val maxAttempts: Int = 3,
    private val baseBackoffMs: Long = 50,
) : DispatchNotificationUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun dispatch(notification: Notification): DispatchResult {
        // --- Idempotency gate ---
        notification.eventId?.let { id ->
            if (!dedupe.markIfFirst(id)) {
                log.info("dedupe skip eventId={} type={}", id, notification.type)
                return DispatchResult.skipped()
            }
        }

        val enabled = channels.filter { it.enabled }
        if (enabled.isEmpty()) {
            log.warn("no enabled channels; nothing dispatched type={}", notification.type)
            return DispatchResult(deduped = false, results = emptyList())
        }

        // --- Concurrent fan-out: the point of Kotlin coroutines ---
        val results: List<ChannelResult> = coroutineScope {
            enabled.map { channel ->
                async { sendWithResilience(channel, notification) }
            }.awaitAll()
        }

        log.info(
            "dispatched type={} recipient={} channels={} success={} failure={}",
            notification.type,
            notification.recipient,
            enabled.size,
            results.count { it is ChannelResult.Success },
            results.count { it is ChannelResult.Failure },
        )
        return DispatchResult(deduped = false, results = results)
    }

    /** Retry with exponential backoff, each attempt bounded by a per-channel timeout. */
    private suspend fun sendWithResilience(
        channel: NotificationChannel,
        notification: Notification,
    ): ChannelResult {
        var lastError: String = "unknown"
        for (attempt in 1..maxAttempts) {
            try {
                withTimeout(perChannelTimeoutMs) {
                    channel.send(notification)
                }
                return ChannelResult.Success(channel.name, attempt)
            } catch (ce: CancellationException) {
                // withTimeout throws TimeoutCancellationException (a CancellationException).
                // Treat a per-channel timeout as a retryable failure; re-throw genuine
                // outer-scope cancellation so we don't swallow it.
                if (ce !is TimeoutCancellationException) throw ce
                lastError = "timeout after ${perChannelTimeoutMs}ms"
                log.warn("channel={} attempt={}/{} {}", channel.name, attempt, maxAttempts, lastError)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                log.warn("channel={} attempt={}/{} failed: {}", channel.name, attempt, maxAttempts, lastError)
            }
            if (attempt < maxAttempts) {
                delay(baseBackoffMs * (1L shl (attempt - 1))) // 50, 100, 200...
            }
        }
        return ChannelResult.Failure(channel.name, maxAttempts, lastError)
    }
}
