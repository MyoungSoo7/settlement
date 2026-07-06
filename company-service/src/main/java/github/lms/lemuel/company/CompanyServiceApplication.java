package github.lms.lemuel.company;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * company-service 독립 부팅 진입점 (ADR 0023).
 *
 * <p>기업별 뉴스 기사 수집·조회와 (Phase 2) 평판 스코어를 제공한다. 자체 DB(lemuel_company) 를
 * 소유하는 DB-per-service 이며, Phase 1 은 다른 서비스와 코드·DB·이벤트 의존이 전혀 없다.
 *
 * <p>★ financial-statements-service 와 같은 이유로 베이스 패키지가
 * {@code github.lms.lemuel.company} 다 — 루트 스캔 시 따라오는 shared-common(JWT·Outbox·Kafka) 이
 * Phase 1 에는 죽은 무게라서 스캔 범위를 한정하고 자체 최소 SecurityConfig 를 둔다.
 * Phase 3(outbox 이벤트 발행)에서 shared-common 의존이 추가된다.
 */
@SpringBootApplication
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
