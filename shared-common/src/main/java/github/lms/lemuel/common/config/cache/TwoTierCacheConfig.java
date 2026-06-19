package github.lms.lemuel.common.config.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.UUID;

/**
 * 2-tier 캐시(L1 Caffeine + L2 Redis) 설정 — opt-in.
 *
 * <p>{@code app.cache.two-tier.enabled=true} 이고 Redis 가 클래스패스에 있을 때만 활성화된다.
 * 활성화되면 로컬 전용 {@link github.lms.lemuel.common.config.CacheConfig} 는 back off 하고
 * 이 설정의 {@link TwoTierCacheManager} 가 {@code cacheManager} 로 등록된다.
 *
 * <p>수평 확장 시 인스턴스마다 L1 이 중복 미스를 내고 서로 stale 해지는 문제를, 공유 L2(Redis)와
 * Pub/Sub 무효화로 해소한다. L2 직렬화는 도메인 setter 부작용/방어적 복사를 피하려 <b>필드 기반</b>
 * Jackson 을 쓰고, gadget 역직렬화 방어를 위해 polymorphic typing 을 안전 패키지로 제한한다.
 */
@Configuration
@EnableCaching
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnProperty(name = "app.cache.two-tier.enabled", havingValue = "true")
public class TwoTierCacheConfig {

    /** 인스턴스 식별자 — Pub/Sub 무효화에서 자기 메시지를 걸러내는 데 쓴다. */
    private final String originId = "cache-node-" + UUID.randomUUID();

    @Value("${app.cache.two-tier.l1-ttl-seconds:60}")
    private long l1TtlSeconds;

    @Value("${app.cache.two-tier.l2-ttl-seconds:600}")
    private long l2TtlSeconds;

    @Value("${app.cache.two-tier.max-size:500}")
    private long l1MaxSize;

    /**
     * L2 직렬화기. 필드 가시성만 켜고(getter/setter/creator off) 무인자 생성자로 인스턴스화 후
     * 필드에 직접 주입 → setter 부작용(updatedAt 재설정 등)·방어적 복사 없이 충실히 round-trip.
     * polymorphic 타입 정보(@class)는 안전 패키지로 제한.
     */
    // GenericJackson2JsonRedisSerializer 는 Spring Boot 4 / Jackson 3 전환기에 removal 표기됐으나,
    // 멀티타입 캐시값(Product/Category/List<...>)에 @class 다형 타입을 부여하는 제너릭 직렬화기로
    // 현 버전(3.5.x)에선 이것이 올바른 선택이다. Jackson 3 기반 대체가 안정화되면 교체한다.
    @SuppressWarnings("removal")
    @Bean
    public RedisTemplate<String, Object> cacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("github.lms.lemuel.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.lang.")
                .build();

        ObjectMapper om = new ObjectMapper();
        om.findAndRegisterModules();   // 클래스패스의 JavaTimeModule 등 런타임 탐지 (컴파일 의존성 불필요)
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setVisibility(om.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        // EVERYTHING: final 스칼라(Long/BigDecimal 등)까지 타입정보를 부여해 List<Long> 등이
        // 역직렬화 시 Integer 로 변질되지 않도록 한다(round-trip 충실성). 보안은 ptv 로 패키지 제한.
        om.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(GenericJackson2JsonRedisSerializer.builder().objectMapper(om).build());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheInvalidationPublisher cacheInvalidationPublisher(StringRedisTemplate stringRedisTemplate) {
        return new CacheInvalidationPublisher(stringRedisTemplate, originId);
    }

    @Bean
    public CacheManager cacheManager(RedisTemplate<String, Object> cacheRedisTemplate,
                                     CacheInvalidationPublisher cacheInvalidationPublisher,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new TwoTierCacheManager(
                CacheNames.ALL,
                cacheRedisTemplate,
                cacheInvalidationPublisher,
                Duration.ofSeconds(l1TtlSeconds),
                Duration.ofSeconds(l2TtlSeconds),
                l1MaxSize,
                true,
                meterRegistryProvider.getIfAvailable());   // 레지스트리 없으면 메트릭 생략(로그만)
    }

    @Bean
    public CacheInvalidationListener cacheInvalidationListener(CacheManager cacheManager) {
        return new CacheInvalidationListener((TwoTierCacheManager) cacheManager, originId);
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidationContainer(RedisConnectionFactory connectionFactory,
                                                                    CacheInvalidationListener cacheInvalidationListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheInvalidationListener, new ChannelTopic(CacheInvalidationPublisher.CHANNEL));
        return container;
    }
}
