package github.lms.lemuel.ai.chat.adapter.out.ratelimit;

import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class Bucket4jRateLimiterTest {

    @Test
    @DisplayName("분당 한도 내에서는 통과, 초과 시 429 예외 + Retry-After")
    void perMinuteLimit() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(2, 100);

        assertThatCode(() -> {
            limiter.acquire(1L);
            limiter.acquire(1L);
        }).doesNotThrowAnyException();

        RateLimitExceededException exceeded =
                catchThrowableOfType(RateLimitExceededException.class, () -> limiter.acquire(1L));
        assertThat(exceeded.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("일일 한도가 분당보다 먼저 소진되면 그 시점에 차단된다")
    void perDayLimit() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(10, 1);

        limiter.acquire(1L);

        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("사용자별 독립 버킷 — 한 사용자의 소진이 다른 사용자에 영향 없다")
    void perUserIsolation() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(1, 100);

        limiter.acquire(1L);

        assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refund — 소진된 토큰을 되돌려 다시 통과할 수 있게 한다")
    void refundReturnsToken() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(1, 100);

        limiter.acquire(1L);   // 한도 1 소진
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(RateLimitExceededException.class);

        limiter.refund(1L);    // 되돌리면 다시 1회 가능
        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refund — 버킷이 없는 사용자면 조용히 통과(best-effort)")
    void refundOnMissingBucketIsNoop() {
        Bucket4jRateLimiter limiter = new Bucket4jRateLimiter(1, 100);

        assertThatCode(() -> limiter.refund(999L)).doesNotThrowAnyException();
    }
}
