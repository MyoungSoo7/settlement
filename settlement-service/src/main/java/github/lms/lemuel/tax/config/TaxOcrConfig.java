package github.lms.lemuel.tax.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 세금계산서 OCR 설정 바인딩 등록 (settlement-service 는 ConfigurationPropertiesScan 을 쓰지 않는다). */
@Configuration
@EnableConfigurationProperties(TaxOcrProperties.class)
public class TaxOcrConfig {
}
