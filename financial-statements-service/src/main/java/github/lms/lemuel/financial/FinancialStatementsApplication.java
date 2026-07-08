package github.lms.lemuel.financial;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * financial-statements-service 독립 부팅 진입점.
 *
 * <p>코스피 상장사 요약 재무제표(공개 read-only 데이터)를 제공한다. 자체 DB(lemuel_financial) 를
 * 소유하는 DB-per-service 이며, 다른 서비스와 코드·DB·이벤트 의존이 전혀 없다.
 *
 * <p>★ 다른 서비스와 달리 베이스 패키지가 {@code github.lms.lemuel.financial} 이다 —
 * 루트 스캔 시 따라오는 shared-common(JWT·Outbox·Kafka) 이 이 서비스에는 죽은 무게라서
 * 스캔 범위를 financial 로 한정하고 자체 최소 SecurityConfig 를 둔다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FinancialStatementsApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(FinancialStatementsApplication.class, args);
    }
}
