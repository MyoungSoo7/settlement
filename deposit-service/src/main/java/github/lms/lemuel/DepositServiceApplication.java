package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * deposit-service 독립 부팅 진입점.
 *
 * <p>★ 자체 DB(lemuel_deposit) 를 소유하는 DB-per-service 이므로 독립 {@code @SpringBootApplication} 을 가진다
 * (card/organization/investment/loan 패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 스캔 → deposit 패키지 + shared-common(JWT SecurityConfig·Outbox·
 * 멱등 인프라·Audit·ShedLock) 빈만 잡힌다. 타 서비스 패키지는 build.gradle.kts 의존에 없어 클래스패스에 없다.
 *
 * <p>JPA Auditing 은 {@code deposit.config.JpaAuditingConfig} 로 분리했다 — 여기에 두면
 * {@code @WebMvcTest} 슬라이스가 JPA 메타모델 없이 {@code jpaAuditingHandler} 를 만들려다 전부 깨진다.
 *
 * <p>{@code @EnableScheduling} 은 현재 deposit 코드에 {@code @Scheduled} 메서드가 없어 생략한다
 * (card 의 한도 재산정 배치 같은 필요가 생기면 그때 추가).
 */
@SpringBootApplication
@EnableCaching
public class DepositServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(DepositServiceApplication.class, args);
    }
}
