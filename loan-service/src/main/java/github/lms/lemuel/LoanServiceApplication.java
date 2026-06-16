package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * loan-service 독립 부팅 진입점.
 *
 * <p>★ loan-service 는 자체 DB(lemuel_loan) 를 소유하는 DB-per-service 이므로,
 * 단일 datasource 인 order-service 컨텍스트에 번들될 수 없다. settlement-service(opslab 공유 →
 * 라이브러리 모드 번들) 와 달리 처음부터 독립 {@code @SpringBootApplication} 을 가진다.
 *
 * <p>루트 {@code github.lms.lemuel} 에서 컴포넌트/엔티티/JPA 리포지토리 스캔 →
 * loan 패키지 + shared-common(Outbox·멱등 인프라) 빈만 잡힌다. order/settlement-service 는
 * loan-service 의 의존(build.gradle.kts)에 없어 클래스패스에 존재하지 않으므로 MSA 코드 경계가 유지된다.
 */
@SpringBootApplication
@EnableScheduling
public class LoanServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(LoanServiceApplication.class, args);
    }
}
