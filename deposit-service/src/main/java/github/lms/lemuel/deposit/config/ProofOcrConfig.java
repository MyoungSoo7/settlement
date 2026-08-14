package github.lms.lemuel.deposit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 예치금 증빙 OCR 프로퍼티 바인딩 — deposit-service 는 {@code @ConfigurationPropertiesScan} 을 쓰지
 * 않으므로 명시 등록한다 (card {@code ReceiptOcrConfig} 와 동형).
 */
@Configuration
@EnableConfigurationProperties(ProofOcrProperties.class)
public class ProofOcrConfig {
}
