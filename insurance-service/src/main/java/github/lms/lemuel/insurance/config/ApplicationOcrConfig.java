package github.lms.lemuel.insurance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 청약서류 OCR 프로퍼티 바인딩 — insurance-service 는 {@code @ConfigurationPropertiesScan} 을 쓰지
 * 않으므로 명시 등록한다 (card {@code ReceiptOcrConfig} 와 동형).
 */
@Configuration
@EnableConfigurationProperties(ApplicationOcrProperties.class)
public class ApplicationOcrConfig {
}
