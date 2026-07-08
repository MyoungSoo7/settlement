package github.lms.lemuel.ai.chat.adapter.out.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import github.lms.lemuel.ai.chat.application.port.out.RateLimitPort;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 사용자별 인메모리 rate limiter (bucket4j) — LLM 비용 가드 (설계 §5.4).
 *
 * <p>한 버킷에 분당·일일 두 대역폭을 함께 걸어, 어느 한도든 먼저 소진되면 429 로 변환될
 * {@link RateLimitExceededException} 을 던진다. 단일 인스턴스 전제의 인메모리 구현 —
 * 스케일아웃 시 Redis ProxyManager 로 교체한다(포트 뒤라 계약 불변).
 */
@Component
public class Bucket4jRateLimiter implements RateLimitPort {

    private final int perMinute;
    private final int perDay;

    /** 사용자별 버킷 — 일일 한도 추적을 위해 마지막 접근 후 25시간 보존. */
    private final Cache<Long, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(25))
            .build();

    public Bucket4jRateLimiter(@Value("${app.ai.rate-limit.per-minute:5}") int perMinute,
                               @Value("${app.ai.rate-limit.per-day:100}") int perDay) {
        this.perMinute = perMinute;
        this.perDay = perDay;
    }

    @Override
    public void acquire(Long userId) {
        Bucket bucket = buckets.get(userId, id -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    @Override
    public void refund(Long userId) {
        // 이미 존재하는 버킷에만 1 토큰을 되돌린다(capacity 상한 초과분은 bucket4j 가 무시).
        // 버킷이 없으면(만료·축출) 되돌릴 대상이 없으므로 조용히 통과 — best-effort.
        Bucket bucket = buckets.getIfPresent(userId);
        if (bucket != null) {
            bucket.addTokens(1);
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute).refillIntervally(perMinute, Duration.ofMinutes(1)).build())
                .addLimit(Bandwidth.builder()
                        .capacity(perDay).refillIntervally(perDay, Duration.ofDays(1)).build())
                .build();
    }
}
