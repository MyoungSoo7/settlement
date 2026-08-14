package github.lms.lemuel.card.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 영수증 OCR 프로퍼티 바인딩 — card-service 는 {@code @ConfigurationPropertiesScan} 을 쓰지 않으므로
 * 명시 등록한다.
 */
@Configuration
@EnableConfigurationProperties(ReceiptOcrProperties.class)
public class ReceiptOcrConfig {
}
