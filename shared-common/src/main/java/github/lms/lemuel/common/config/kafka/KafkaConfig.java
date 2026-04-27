package github.lms.lemuel.common.config.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 인프라 설정.
 *
 * <p>app.kafka.enabled=true 일 때만 활성화. 비활성 시 spring-kafka 오토컨피그가
 * 부분적으로 뜨지 않도록 @EnableKafka 를 이 조건부 빈에 모았다.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    /**
     * 결제 완료 이벤트 토픽.
     *
     * <p>파티션 3 — 결제는 payment_id 기준 해시로 분배해 같은 결제의 이벤트 순서 보장.
     * 복제본 1 — 개발/데모용. 프로덕션은 최소 3 권장.
     */
    @Bean
    public NewTopic paymentCapturedTopic() {
        return TopicBuilder.name("lemuel.payment.captured")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7일
                .build();
    }

    @Bean
    public NewTopic paymentRefundedTopic() {
        return TopicBuilder.name("lemuel.payment.refunded")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }
}
