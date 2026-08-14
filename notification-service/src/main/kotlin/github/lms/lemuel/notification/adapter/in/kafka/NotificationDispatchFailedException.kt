package github.lms.lemuel.notification.adapter.`in`.kafka

import github.lms.lemuel.notification.application.ChannelResult

/**
 * 활성 채널 전부가 실패했다 — 이 알림은 어디에도 전달되지 않았다.
 *
 * [IllegalStateException] 을 확장하는 이유는 [KafkaErrorHandlingConfig] 의 "즉시 DLT" 분류에
 * 걸리기 위함이다. Kafka 재시도를 붙이지 않는 근거는 둘이다.
 *  1. 채널은 이미 자체 재시도를 소진했다(채널별 timeout + 3회 백오프) — 2초 뒤 같은 SMTP 가
 *     살아날 확률에 오프셋을 걸어 둘 이유가 없다.
 *  2. [github.lms.lemuel.notification.application.DedupeStore] 가 dispatch 직전에 eventId 를
 *     선점하므로, 재배달은 dedupe 스킵(no-op)으로 끝나고 컨테이너에는 "성공"으로 보인다.
 *     즉 재시도는 유실을 고치지 못하고 가린다.
 *
 * DLT 에 보존되면 사후 분석과 replay 가 가능하다. dedupe TTL(기본 30분)이 지난 뒤 replay 하면
 * 실제로 다시 발송된다.
 */
class NotificationDispatchFailedException(
    topic: String,
    eventId: String?,
    failures: List<ChannelResult.Failure>,
) : IllegalStateException(
    "all channels failed on topic=$topic eventId=$eventId — " +
        failures.joinToString(", ") { "${it.channel}(${it.attempts} attempts): ${it.error}" },
)
