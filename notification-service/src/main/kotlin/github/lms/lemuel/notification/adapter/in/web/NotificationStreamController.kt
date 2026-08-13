package github.lms.lemuel.notification.adapter.`in`.web

import github.lms.lemuel.notification.application.NotificationStream
import github.lms.lemuel.notification.application.StreamSubscription
import github.lms.lemuel.notification.domain.StreamEvent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Wire format of one pushed notification. */
data class StreamEventDto(
    val id: Long,
    val type: String,
    val recipient: String,
    val subject: String,
    val body: String,
    val eventId: String?,
    val occurredAt: String,
) {
    companion object {
        fun from(event: StreamEvent) = StreamEventDto(
            id = event.seq,
            type = event.notification.type.name,
            recipient = event.recipient,
            subject = event.notification.subject,
            body = event.notification.body,
            eventId = event.notification.eventId,
            occurredAt = event.occurredAt.toString(),
        )
    }
}

/** No verified identity — 401. */
class StreamUnauthorizedException(message: String) : RuntimeException(message)

/** No signing key configured — the stream refuses to serve rather than trust. */
class StreamNotConfiguredException(message: String) : RuntimeException(message)

/**
 * Browser-facing push endpoint: `GET /notifications/stream` (SSE).
 *
 * One HTTP connection == one hub subscription. The client's identity comes from
 * its JWT; on reconnect the browser replays `Last-Event-ID` automatically and
 * the hub resends what it missed (bounded by the retention window).
 *
 * A heartbeat comment keeps idle connections alive — proxies happily kill a
 * connection that is silent for a minute, and a dead peer is otherwise only
 * noticed on the next real event.
 */
@RestController
@RequestMapping("/notifications")
class NotificationStreamController(
    private val stream: NotificationStream,
    private val identities: JwtSubscriberIdentityResolver,
    @Value("\${app.stream.timeout-ms:1800000}") private val timeoutMs: Long,
    @Value("\${app.stream.heartbeat-seconds:15}") heartbeatSeconds: Long,
    @Value("\${app.stream.reconnect-hint-ms:2000}") private val reconnectHintMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val connections = ConcurrentHashMap.newKeySet<Connection>()

    private val heartbeats = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sse-heartbeat").apply { isDaemon = true }
    }

    init {
        if (heartbeatSeconds > 0) {
            heartbeats.scheduleAtFixedRate(
                ::pingAll, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS,
            )
        }
    }

    // charset is spelled out: SSE is UTF-8 by spec, but an unqualified
    // text/event-stream leaves intermediaries (and any non-browser client)
    // guessing, and Korean subjects arrive as mojibake when they guess latin-1.
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"])
    fun stream(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestParam(name = "token", required = false) tokenParam: String?,
        @RequestHeader(name = LAST_EVENT_ID, required = false) lastEventIdHeader: String?,
        @RequestParam(name = "lastEventId", required = false) lastEventIdParam: String?,
    ): SseEmitter {
        if (!identities.configured) {
            throw StreamNotConfiguredException(
                "notification push stream requires app.security.jwt.secret (>= 32 bytes)",
            )
        }
        val identity = identities.resolve(JwtSubscriberIdentityResolver.tokenFrom(authorization, tokenParam))
            ?: throw StreamUnauthorizedException("a valid JWT is required to open the notification stream")

        val connection = Connection(SseEmitter(timeoutMs))
        connections += connection

        // Bytes up front: EventSource fires `onopen` only once something
        // arrives, and the retry hint fixes the client's reconnect backoff
        // instead of leaving it to browser defaults.
        connection.send(SseEmitter.event().comment("connected").reconnectTime(reconnectHintMs))

        connection.subscription = stream.subscribe(
            recipients = identity.recipients,
            lastEventId = parseLastEventId(lastEventIdHeader, lastEventIdParam),
        ) { event ->
            connection.send(
                SseEmitter.event()
                    .id(event.seq.toString())
                    .name(EVENT_NAME)
                    .data(StreamEventDto.from(event), MediaType.APPLICATION_JSON),
            )
        }

        connection.emitter.onCompletion { close(connection) }
        connection.emitter.onTimeout { close(connection) }
        connection.emitter.onError { close(connection) }

        log.debug("stream opened subject={} identities={}", identity.subject, identity.recipients)
        return connection.emitter
    }

    /**
     * A malformed resume point degrades to "live only" instead of failing the
     * request: a client stuck with a bad stored id would otherwise reconnect
     * into an error forever.
     */
    private fun parseLastEventId(header: String?, param: String?): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: param?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
        return raw.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun pingAll() {
        connections.forEach { connection ->
            runCatching { connection.send(SseEmitter.event().comment("ping")) }
        }
    }

    private fun close(connection: Connection) {
        if (connections.remove(connection)) {
            connection.subscription?.cancel()
        }
    }

    @PreDestroy
    fun shutdown() {
        heartbeats.shutdownNow()
        connections.toList().forEach { connection ->
            close(connection)
            runCatching { connection.emitter.complete() }
        }
    }

    /**
     * One open SSE connection. Sends are serialised: the publishing thread and
     * the heartbeat thread would otherwise interleave frames on one emitter,
     * which SseEmitter does not tolerate.
     */
    private inner class Connection(val emitter: SseEmitter) {
        @Volatile
        var subscription: StreamSubscription? = null

        private val writeLock = Any()

        fun send(event: SseEmitter.SseEventBuilder) {
            synchronized(writeLock) {
                try {
                    emitter.send(event)
                } catch (e: Exception) {
                    // Client gone (closed tab, dead proxy) or emitter already
                    // completed. Tear the subscription down and let the caller
                    // (the hub) drop this listener.
                    close(this)
                    throw e
                }
            }
        }
    }

    companion object {
        /** Standard SSE resume header, replayed by EventSource automatically. */
        const val LAST_EVENT_ID = "Last-Event-ID"
        private const val EVENT_NAME = "notification"
    }
}
