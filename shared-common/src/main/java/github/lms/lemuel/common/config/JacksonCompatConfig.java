package github.lms.lemuel.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.common.outbox.OutboxJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 2 ↔ Jackson 3 호환용 브릿지.
 *
 * <p>Spring Boot 4 + Spring 7 은 {@code JacksonAutoConfiguration} 을 Jackson 3
 * ({@code tools.jackson.core}) 기반으로 재작성해 {@code JsonMapper} 빈만 등록한다.
 * 하지만 레거시 도메인 코드(예: {@code OutboxBackedEventPublisher},
 * {@code PaymentEventKafkaConsumer}) 는 여전히 Jackson 2 의
 * {@code com.fasterxml.jackson.databind.ObjectMapper} 를 생성자 주입으로 요구한다.
 *
 * <p>점진적 마이그레이션 전략: 임시로 Jackson 2 ObjectMapper 빈을 수동 등록해
 * 기존 호출부를 유지한다. 모든 사용처가 Jackson 3 또는 {@code JsonMapper} 로
 * 이전되면 본 설정은 제거한다.
 */
@Configuration
public class JacksonCompatConfig {

    /**
     * 범용 Jackson 2 ObjectMapper. {@code @Primary} 는 아래 outbox 전용 빈이 추가되면서
     * 타입 주입(82곳)이 모호해지지 않도록 하는 것 — 기존 주입부의 동작은 그대로다.
     */
    @Bean
    @Primary
    public ObjectMapper jacksonLegacyObjectMapper() {
        // JavaTimeModule 미등록 시 java.time 필드 직렬화가 InvalidDefinitionException 으로 실패
        // (RedisCartAdapter 의 LocalDateTime 등). 날짜는 ISO-8601 문자열로 통일.
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Outbox payload 전용 — 금액(BigDecimal)을 plain string 으로 직렬화(DATA-STANDARD N5).
     * 발행 어댑터만 {@code @Qualifier("outboxObjectMapper")} 로 주입받는다. 근거는 {@link OutboxJson}.
     */
    @Bean("outboxObjectMapper")
    public ObjectMapper outboxObjectMapper() {
        return OutboxJson.mapper();
    }
}
