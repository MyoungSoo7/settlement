package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * card-service 독립 부팅 진입점.
 *
 * <p>★ 자체 DB(lemuel_card) 를 소유하는 DB-per-service 이므로 독립 {@code @SpringBootApplication} 을 가진다
 * (organization/investment/loan 패턴 미러링).
 *
 * <p>루트 {@code github.lms.lemuel} 에서 스캔 → card 패키지 + shared-common(JWT SecurityConfig·Outbox·
 * 멱등 인프라·Audit) 빈만 잡힌다. 타 서비스 패키지는 build.gradle.kts 의존에 없어 클래스패스에 없다.
 *
 * <p>{@code @EnableScheduling} 은 일 1회 한도 재산정(Task 13)에 필요하다.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class CardServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(CardServiceApplication.class, args);
    }
}
