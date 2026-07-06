package github.lms.lemuel.company;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * company-service 독립 부팅 진입점 (ADR 0023).
 *
 * <p>기업별 뉴스 기사 수집·조회(Phase 1)와 평판 스코어(Phase 2)를 제공하고, Phase 3 부터
 * 평판 등급 변동을 Kafka 이벤트로 발행한다. 자체 DB(lemuel_company) 를 소유하는 DB-per-service.
 *
 * <p>★ 스캔 범위를 {@code github.lms.lemuel.company} + {@code github.lms.lemuel.common.outbox} 로
 * <b>한정</b>한다 — shared-common 의 Outbox·멱등 인프라만 물고, JWT/audit/ratelimit 스택은 여전히
 * 제외해 자체 최소 SecurityConfig 를 유지한다(financial-statements-service 와 같은 격리 철학).
 * {@code @EnableScheduling} 은 Outbox 폴러(OutboxPublisherScheduler)의 {@code @Scheduled} 를 켠다.
 */
@SpringBootApplication(scanBasePackages = {
        "github.lms.lemuel.company",
        "github.lms.lemuel.common.outbox"
})
@EntityScan(basePackages = {
        "github.lms.lemuel.company",
        "github.lms.lemuel.common.outbox"
})
@EnableJpaRepositories(basePackages = {
        "github.lms.lemuel.company",
        "github.lms.lemuel.common.outbox"
})
@EnableScheduling
@ConfigurationPropertiesScan
public class CompanyServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(CompanyServiceApplication.class, args);
    }
}
