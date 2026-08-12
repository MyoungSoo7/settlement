package github.lms.lemuel.notification.adapter.`in`.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import github.lms.lemuel.notification.application.ChannelResult
import github.lms.lemuel.notification.application.DispatchNotificationUseCase
import github.lms.lemuel.notification.application.DispatchResult
import github.lms.lemuel.notification.domain.Notification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 컨슈머의 실패 전파 계약.
 *
 * 배경: 이 리스너는 원래 `catch (e: Exception) { log.error(...) }` 로 모든 실패를 삼켰다.
 * 컨테이너 입장에서는 정상 처리로 보여 오프셋이 커밋되고 메시지는 사라진다 — DLT 배선을
 * 붙여도 예외가 밖으로 나오지 않으면 재시도도 격리도 일어나지 않는다. 그래서 "삼키지 않는다"가
 * DLQ 의 전제 조건이고, 그 계약을 여기서 고정한다.
 */
class DomainEventListenerTest {

    private val objectMapper = ObjectMapper()

    private class RecordingDispatcher(
        private val failure: Throwable? = null,
        private val result: DispatchResult = DispatchResult(deduped = false, results = emptyList()),
    ) : DispatchNotificationUseCase {
        var calls = 0
        override suspend fun dispatch(notification: Notification): DispatchResult {
            calls += 1
            failure?.let { throw it }
            return result
        }
    }

    private fun deliver(dispatcher: DispatchNotificationUseCase, payload: String) =
        DomainEventListener(dispatcher, objectMapper).onEvent(
            payload = payload,
            topic = "lemuel.settlement.confirmed",
            key = "SET-1",
            eventIdHeader = "11111111-1111-1111-1111-111111111111",
        )

    @Test
    @DisplayName("정상 이벤트는 디스패치되고 예외 없이 끝난다")
    fun dispatchesValidEvent() {
        val dispatcher = RecordingDispatcher()

        assertDoesNotThrow { deliver(dispatcher, """{"sellerId":"S1","settlementId":"SET-1"}""") }

        assertEquals(1, dispatcher.calls)
    }

    @Test
    @DisplayName("디스패치 실패는 삼키지 않고 밖으로 던진다 — 에러 핸들러가 재시도·DLT 를 결정해야 한다")
    fun propagatesDispatchFailure() {
        val dispatcher = RecordingDispatcher(IllegalStateException("channel down"))

        val thrown = assertThrows(IllegalStateException::class.java) {
            deliver(dispatcher, """{"sellerId":"S1"}""")
        }

        assertEquals("channel down", thrown.message)
    }

    @Test
    @DisplayName("모든 채널이 실패하면 던진다 — 아무 데도 전달되지 않은 알림이 조용히 커밋되면 유실이다")
    fun propagatesAllChannelsFailed() {
        val dispatcher = RecordingDispatcher(
            result = DispatchResult(
                deduped = false,
                results = listOf(
                    ChannelResult.Failure("email", attempts = 3, error = "smtp down"),
                    ChannelResult.Failure("sse", attempts = 3, error = "no subscriber"),
                ),
            ),
        )

        val thrown = assertThrows(NotificationDispatchFailedException::class.java) {
            deliver(dispatcher, """{"sellerId":"S1","settlementId":"SET-1"}""")
        }

        // 즉시-DLT 분류(IllegalStateException 계열)여야 한다. 채널은 이미 자체 재시도를 소진했고,
        // dedupe 가 eventId 를 선점한 뒤라 Kafka 재배달은 무의미한 no-op 이 된다.
        assertInstanceOf(IllegalStateException::class.java, thrown)
    }

    @Test
    @DisplayName("한 채널이라도 성공하면 던지지 않는다 — 이미 전달된 알림을 DLT 로 보내면 재발송 중복이 된다")
    fun doesNotThrowOnPartialSuccess() {
        val dispatcher = RecordingDispatcher(
            result = DispatchResult(
                deduped = false,
                results = listOf(
                    ChannelResult.Success("sse", attempts = 1),
                    ChannelResult.Failure("email", attempts = 3, error = "smtp down"),
                ),
            ),
        )

        assertDoesNotThrow { deliver(dispatcher, """{"sellerId":"S1","settlementId":"SET-1"}""") }
    }

    @Test
    @DisplayName("중복으로 스킵된 이벤트는 실패가 아니다 — 멱등 스킵을 DLT 로 보내면 안 된다")
    fun doesNotThrowOnDedupedSkip() {
        val dispatcher = RecordingDispatcher(result = DispatchResult.skipped())

        assertDoesNotThrow { deliver(dispatcher, """{"sellerId":"S1","settlementId":"SET-1"}""") }
    }

    @Test
    @DisplayName("파싱 불가 페이로드는 조용히 스킵하지 않고 던진다 — 계약 드리프트를 DLT 에 보존한다")
    fun propagatesUnparseablePayload() {
        val dispatcher = RecordingDispatcher()

        assertThrows(UnparseableEventPayloadException::class.java) {
            deliver(dispatcher, "not-json-at-all")
        }
        // 파싱이 깨졌으면 폴백 수신자에게 엉뚱한 GENERIC 알림을 만들어 보내지 않는다(원래 의도 보존).
        assertEquals(0, dispatcher.calls)
    }
}
