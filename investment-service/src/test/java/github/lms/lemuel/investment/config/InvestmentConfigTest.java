package github.lms.lemuel.investment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * investment-service 전용 @Configuration 빈 팩토리 단위 검증
 * (Caffeine 캐시 매니저에 investmentScores 캐시 등록 / RestClient.Builder 빈 생성).
 */
class InvestmentConfigTest {

    @Test
    @DisplayName("캐시 매니저는 investmentScores + 초보 체크 위성 축 캐시를 제공한다")
    void cacheManagerExposesInvestmentScores() {
        CacheManager manager = new InvestmentCacheConfig().investmentCacheManager();

        assertThat(manager.getCache(InvestmentCacheConfig.INVESTMENT_SCORES)).isNotNull();
        assertThat(manager.getCache(InvestmentCacheConfig.BEGINNER_NEWS_FEED)).isNotNull();
        assertThat(manager.getCache(InvestmentCacheConfig.BEGINNER_DAILY_CLOSES)).isNotNull();
        assertThat(manager.getCache(InvestmentCacheConfig.BEGINNER_MACRO_INDICATORS)).isNotNull();
    }

    @Test
    @DisplayName("RestClient.Builder 빈은 클라이언트를 만들 수 있다")
    void restClientBuilderBuilds() {
        RestClient.Builder builder = new HttpClientConfig().restClientBuilder();

        assertThat(builder).isNotNull();
        assertThat(builder.build()).isNotNull();
    }
}
