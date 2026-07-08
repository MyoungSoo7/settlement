package github.lms.lemuel.ai.chat.application.exception;

/** 사용자별 채팅 호출 상한 초과 — 429 + Retry-After 로 변환된다. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("채팅 호출 한도를 초과했습니다. " + retryAfterSeconds + "초 후 다시 시도해 주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
