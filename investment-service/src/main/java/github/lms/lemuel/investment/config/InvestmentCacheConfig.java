package github.lms.lemuel.investment.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * investment-service 전용 Caffeine 캐시.
 *
 * <p>shared-common 의 {@code CacheConfig} 는 {@code CacheNames.ALL} 고정 목록으로 캐시를 등록하므로
 * 이 서비스 전용 캐시({@code investmentScores})가 없다 — 투자점수 캐시 이름은 investment 도메인 소유라
 * 공유 라이브러리에 올리지 않고 여기서 {@link Primary} 매니저로 등록한다.
 * 투자점수는 financial 공개 API 조회 + 점수 산정 비용이 있어 10분 TTL 로 캐시한다.
 */
@Configuration
@EnableCaching
public class InvestmentCacheConfig {

    public static final String INVESTMENT_SCORES = "investmentScores";

    @Bean
    @Primary
    public CacheManager investmentCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(INVESTMENT_SCORES);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(500)
        );
        return manager;
    }
}
