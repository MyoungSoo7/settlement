package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * organization-service 독립 부팅 진입점.
 *
 * <p>★ organization-service 는 자체 DB(lemuel_organization) 를 소유하는 DB-per-service 이므로
 * 처음부터 독립 {@code @SpringBootApplication} 을 가진다(investment/loan 패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 컴포넌트/엔티티/JPA 리포지토리 스캔 →
 * organization 패키지 + shared-common(JWT SecurityConfig·Outbox·멱등 인프라·JacksonCompatConfig) 빈만 잡힌다.
 * 타 서비스 패키지는 build.gradle.kts 의존에 없어 클래스패스에 없으므로 MSA 코드 경계가 유지된다 —
 * 타 서비스 연계는 Outbox → Kafka 이벤트 발행으로만 한다.
 */
@SpringBootApplication
@EnableCaching
public class OrganizationServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(OrganizationServiceApplication.class, args);
    }
}
