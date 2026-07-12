package github.lms.lemuel.company;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * company-service 독립 부팅 진입점 (ADR 0023).
 *
 * <p>기업별 뉴스 기사 수집·조회(Phase 1)와 평판 스코어(Phase 2)를 제공하고, Phase 3 부터
 * 평판 등급 변동을 Kafka 이벤트로 발행한다. 자체 DB(lemuel_company) 를 소유하는 DB-per-service.
 *
 * <p>★ 스캔 범위를 {@code github.lms.lemuel.company} 로 <b>한정</b>한다 — JWT/audit/ratelimit 스택은
 * 제외해 자체 최소 SecurityConfig 를 유지한다(financial-statements-service 와 같은 격리 철학).
 * shared-common Outbox·멱등 인프라의 컴포넌트 스캔과 JPA 리포지토리/엔티티 등록은 {@link
 * github.lms.lemuel.company.config.PersistenceConfig} 로 분리했다 — 앱 클래스(=@SpringBootConfiguration)에
 * {@code @EnableJpaRepositories} 가 붙어 있으면 {@code @WebMvcTest} 웹 슬라이스가 JPA 를 강제로 물어
 * 컨텍스트가 깨지기 때문이다. {@code @EnableScheduling} 은 Outbox 폴러의 {@code @Scheduled} 를 켠다.
 */
@SpringBootApplication(scanBasePackages = "github.lms.lemuel.company")
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
