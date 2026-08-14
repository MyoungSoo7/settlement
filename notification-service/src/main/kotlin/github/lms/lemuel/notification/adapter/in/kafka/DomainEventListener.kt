package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import github.lms.lemuel.notification.application.ChannelResult
import github.lms.lemuel.notification.application.DispatchNotificationUseCase
import github.lms.lemuel.notification.domain.NotificationTemplate
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

/**
 * Inbound Kafka adapter. Consumes settlement domain events and maps each to a
 * [github.lms.lemuel.notification.domain.Notification], then dispatches.
 *
 * Gated by `app.kafka.enabled` (default false) so the app boots and the REST/demo
 * path works with NO broker reachable — critical for tests and containers.
 * Kafka health indicator is also disabled (see application.yml).
 *
 * Failures are NOT caught here. This adapter used to wrap everything in
 * `catch (e: Exception) { log.error(...) }`, which looked like resilience but meant the
 * container saw every poison message as processed: the offset was committed and the
 * message was gone. Retry/quarantine is [KafkaErrorHandlingConfig]'s job — it can only
 * do that job if the exception actually escapes this method.
 *
 * That applies to the dispatch RESULT too, not just thrown exceptions: the dispatcher reports
 * a total delivery failure by returning [github.lms.lemuel.notification.application.ChannelResult.Failure]
 * for every channel, so this adapter turns that state into [NotificationDispatchFailedException].
 * Otherwise a notification that reached nobody would still commit its offset.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = ["enabled"], havingValue = "true")
class DomainEventListener(
    private val dispatcher: DispatchNotificationUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            "lemuel.settlement.confirmed",
            "lemuel.payment.confirmed",   // payment-webhook-service 발행(내부 계약)
            "lemuel.payment.captured",    // 실 결제 이벤트(기존 Java 결제 파이프라인)
            "lemuel.payment.refunded",
            "lemuel.investment.executed",
        ],
        groupId = "\${spring.kafka.consumer.group-id:notification-service}",
    )
    fun onEvent(
        @Payload payload: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
        @Header(name = "event_id", required = false) eventIdHeader: String?,
    ) {
        // Parse failure is NOT silently degraded to an empty map, and no longer silently
        // skipped either: an unparseable payload on a contract topic means contract drift.
        // Throwing does NOT fabricate a spurious GENERIC alert to the fallback recipient
        // (the original reason for skipping) — it hands the record to the error handler,
        // which routes it straight to `<topic>.DLT` without retrying. Poison messages
        // still never kill the container; they get quarantined instead of vanishing.
        @Suppress("UNCHECKED_CAST")
        val fields: Map<String, Any?> = runCatching {
            objectMapper.readValue(payload, Map::class.java) as Map<String, Any?>
        }.getOrElse { parseError ->
            log.warn(
                "unparseable event payload — routing to DLT (contract drift?) topic={} key={} cause={}",
                topic, key, parseError.toString(),
            )
            throw UnparseableEventPayloadException(topic, key, parseError)
        }

            // eventId for idempotency: the Java Outbox publisher carries the unique
            // event UUID in the `event_id` HEADER only (payload has no eventId field,
            // and the kafka key is the aggregateId — shared across events of the same
            // aggregate, e.g. payment.captured then payment.refunded of one paymentId,
            // so keying dedupe on it drops the second event). Header first; payload
            // fields and key remain as fallbacks for non-Outbox producers (Go webhook).
        val eventId = (eventIdHeader ?: fields["eventId"] ?: fields["id"] ?: key)?.toString()
        val notification = NotificationTemplate.fromEvent(topic, fields, eventId)

        val result = runBlocking { dispatcher.dispatch(notification) }
        log.info(
            "kafka event topic={} eventId={} deduped={} allSucceeded={}",
            topic, eventId, result.deduped, result.allSucceeded,
        )

        // 활성 채널이 전부 실패했다면 이 알림은 어디에도 도달하지 않았다. 여기서 그냥 리턴하면
        // 오프셋이 커밋되고 메시지는 사라진다 — dispatch 가 예외 대신 결과 객체를 돌려주기 때문에,
        // "삼키지 않는다"는 계약은 파싱 실패뿐 아니라 이 경로에서도 명시적으로 지켜야 한다.
        //
        // 세 가지 비대칭이 의도된 것이다:
        //  - 부분 성공(anySucceeded)은 던지지 않는다. 이미 도달한 채널이 있는데 DLT 로 보내면
        //    replay 시 그 채널로 중복 발송된다. 실패 채널은 dispatcher 가 warn 으로 남긴다.
        //  - 중복 스킵(deduped)은 실패가 아니다 — 앞선 배달이 이미 처리했다.
        //  - results 가 비어 있는 경우는 "활성 채널 0개"라는 배포 설정 오류지 메시지 문제가 아니다.
        //    스트림 전량을 DLT 로 밀어 넣는 대신 dispatcher 의 warn + 설정 검증에 맡긴다.
        if (!result.deduped && result.results.isNotEmpty() && !result.anySucceeded) {
            throw NotificationDispatchFailedException(
                topic, eventId, result.results.filterIsInstance<ChannelResult.Failure>(),
            )
        }
    }
}
