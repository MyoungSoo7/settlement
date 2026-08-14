package github.lms.lemuel.notification.adapter.`in`.kafka

/**
 * 계약 토픽에 파싱 불가 페이로드가 들어왔다 — 계약 드리프트 신호.
 *
 * 재시도해도 같은 바이트가 다시 파싱 실패하므로 [KafkaErrorHandlingConfig] 가 재시도 없이
 * 즉시 DLT 로 격리한다. [IllegalArgumentException] 을 확장해 "입력 계약 위반" 의미를 유지하되,
 * 임의의 IAE 와 로그·분류에서 구분된다(도메인의 `NotificationInvariantViolationException` 과 같은 관례).
 *
 * 이전에는 이 상황을 warn 로그 + skip 으로 처리했다. 폴백 수신자에게 엉뚱한 GENERIC 알림을
 * 만들어 보내지 않으려는 의도였는데(그 의도는 여전히 유효 — 던지는 것도 알림을 만들지 않는다),
 * 결과적으로 원본 메시지가 흔적 없이 사라졌다. 이제는 DLT 에 보존되어 사후 분석·replay 가 된다.
 */
class UnparseableEventPayloadException(
    topic: String,
    key: String?,
    cause: Throwable,
) : IllegalArgumentException("unparseable event payload on contract topic=$topic key=$key", cause)
