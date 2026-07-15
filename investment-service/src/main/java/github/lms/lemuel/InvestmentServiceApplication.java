package github.lms.lemuel;

import github.lms.lemuel.investment.config.ScreeningProperties;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * investment-service 독립 부팅 진입점.
 *
 * <p>★ investment-service 는 자체 DB(lemuel_investment) 를 소유하는 DB-per-service 이므로,
 * 단일 datasource 인 order-service 컨텍스트에 번들될 수 없다. loan-service 와 동일하게
 * 처음부터 독립 {@code @SpringBootApplication} 을 가진다(패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 컴포넌트/엔티티/JPA 리포지토리 스캔 →
 * investment 패키지 + shared-common(JWT SecurityConfig·Outbox·멱등 인프라·JacksonCompatConfig) 빈만 잡힌다.
 * order/settlement/financial-service 는 investment-service 의 의존(build.gradle.kts)에 없어 클래스패스에
 * 존재하지 않으므로 MSA 코드 경계가 유지된다 — 재무제표는 HTTP(financial 공개 API), 재원은 Kafka 이벤트로만 받는다.
 */
@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableConfigurationProperties(ScreeningProperties.class)
public class InvestmentServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(InvestmentServiceApplication.class, args);
    }
}
