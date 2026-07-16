package github.lms.lemuel.company.adapter.out.analysis;

import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.domain.ArticleSentiment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Gemini 호출량 상한 가드 (ADR 0023 Phase 4 — 무료티어 초과/과금 방지).
 *
 * <p>{@code provider=gemini} 일 때 실제 {@link GeminiSentimentAnalyzer} 앞에 {@code @Primary} 로 끼어들어,
 * <b>일일 호출 상한</b>(도달 시 그날은 키워드 폴백)과 <b>분당 스로틀</b>(호출 간 최소 간격)을 강제한다.
 * fail-open 철학 유지 — 상한을 넘겨도 평판 산정은 키워드로 계속된다(예외 없음).
 *
 * <p>재계산은 단일 스레드 순차 실행이지만 방어적으로 상태 접근을 동기화한다. 상한/간격은
 * {@code app.company.sentiment.gemini.daily-quota}·{@code .min-interval-ms} 로 조절(env override).
 *
 * <p>참고: 캐시({@code SentimentCachePort})가 신규 기사만 분석하도록 호출량을 이미 줄이므로 이 상한은
 * 평상시엔 거의 닿지 않는 <b>안전망</b>이다. 상한 도달분(대량 콜드스타트 등)은 키워드로 캐시되며 이후
 * 재승격되지 않는다(비용 보장 우선 — 허용된 트레이드오프).
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.company.sentiment.provider", havingValue = "gemini")
public class QuotaGuardedSentimentAnalyzer implements AnalyzeSentimentPort {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuardedSentimentAnalyzer.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AnalyzeSentimentPort delegate;
    private final AnalyzeSentimentPort fallback = new KeywordSentimentAnalyzer();
    private final int dailyQuota;
    private final long minIntervalMs;

    private LocalDate day = LocalDate.now(KST);
    private int usedToday = 0;
    private long lastCallMs = 0L;
    private boolean warnedToday = false;

    public QuotaGuardedSentimentAnalyzer(GeminiSentimentAnalyzer delegate, GeminiSentimentProperties properties) {
        this.delegate = delegate;
        this.dailyQuota = properties.dailyQuota();
        this.minIntervalMs = properties.minIntervalMs();
        log.info("Gemini 호출 상한 가드 활성 — dailyQuota={}, minIntervalMs={}", dailyQuota, minIntervalMs);
    }

    @Override
    public ArticleSentiment analyze(String title, String summary) {
        if (!reserveSlot()) {
            return fallback.analyze(title, summary);
        }
        return delegate.analyze(title, summary);
    }

    /** 오늘 예산에서 1콜을 예약하고 스로틀을 적용한다. 예산 소진 시 false(키워드 폴백). */
    private synchronized boolean reserveSlot() {
        LocalDate today = LocalDate.now(KST);
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
            warnedToday = false;
        }
        if (usedToday >= dailyQuota) {
            if (!warnedToday) {
                log.warn("Gemini 일일 호출 상한({}) 도달 — 오늘 남은 기사는 키워드 폴백", dailyQuota);
                warnedToday = true;
            }
            return false;
        }
        usedToday++;
        throttle();
        return true;
    }

    /** 분당 호출 상한(무료티어 RPM) 보호 — 직전 호출과 최소 간격을 둔다. */
    private void throttle() {
        if (minIntervalMs <= 0) {
            return;
        }
        long wait = lastCallMs + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallMs = System.currentTimeMillis();
    }
}
