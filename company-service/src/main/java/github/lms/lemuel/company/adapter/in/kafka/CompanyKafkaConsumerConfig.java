package github.lms.lemuel.company.adapter.in.kafka;

import github.lms.lemuel.common.config.kafka.KafkaConsumerErrorHandlingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * company 컨슈머에 공용 Kafka 에러 핸들링(재시도 → DLT) 배선을 붙인다.
 *
 * <p>company 는 스캔 범위를 {@code github.lms.lemuel.company} 로 <b>한정</b>하므로(앱 클래스 주석 참조)
 * shared-common 의 {@link KafkaConsumerErrorHandlingConfig} 가 자동으로 잡히지 않는다 — 명시 {@code @Import}
 * 가 없으면 Spring Kafka 기본 핸들러로 조용히 떨어져 재시도 소진 메시지가 유실된다.
 * 이 클래스는 company 스캔 범위 안에 있으므로 런타임에 픽업된다.
 *
 * <p>공용 설정 자체가 {@code @ConditionalOnProperty(app.kafka.enabled=true)} 이므로
 * Kafka 를 끈 프로파일에서는 아무 빈도 뜨지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Import(KafkaConsumerErrorHandlingConfig.class)
public class CompanyKafkaConsumerConfig {
}
