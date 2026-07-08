package github.lms.lemuel.commondata.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 활성화 — 소스 카탈로그({@code dataSources})/수집 레코드({@code dataRecords}) 조회 캐시.
 *
 * <p>Caffeine 스펙(maximumSize/expireAfterWrite)은 application.yml {@code spring.cache.caffeine.spec}
 * 이 담당하고, 여기서는 {@code @EnableCaching} 으로 {@code @Cacheable}/{@code @CacheEvict} 프록시만 켠다.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
