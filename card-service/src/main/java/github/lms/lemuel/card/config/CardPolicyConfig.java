package github.lms.lemuel.card.config;

import github.lms.lemuel.card.domain.CardLimitPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 한도 정책 배선. 정책 자체는 프레임워크를 모르는 순수 도메인 객체라(ArchUnit 이 강제) 설정 주입을
 * 도메인 밖 이 지점에서 한 번만 한다 — 인정비율·최소한도가 코드가 아니라 배포 설정으로 조정된다.
 */
@Configuration
public class CardPolicyConfig {

    @Bean
    public CardLimitPolicy cardLimitPolicy(
            @Value("${app.card.limit.recognition-ratio:0.70}") BigDecimal recognitionRatio,
            @Value("${app.card.limit.minimum:300000}") BigDecimal minimumLimit) {
        return new CardLimitPolicy(recognitionRatio, minimumLimit);
    }
}
