package github.lms.lemuel.market;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * market-service 독립 부팅 진입점.
 *
 * <p>KRX(한국거래소) 상장사 일별 시세·시가총액(공공데이터포털 금융위 주식시세정보로 공개된
 * read-only 데이터)을 제공한다. 자체 DB(lemuel_market) 를 소유하는 DB-per-service 이며,
 * 다른 서비스와 코드·DB·이벤트 의존이 전혀 없다. stockCode(6자리 단축코드)를 financial/company
 * 와 공용 비즈니스 키로 쓰지만 조인은 소비측 몫이다.
 *
 * <p>★ 다른 서비스와 달리 베이스 패키지가 {@code github.lms.lemuel.market} 이다 —
 * 루트 스캔 시 따라오는 shared-common(JWT·Outbox·Kafka) 이 이 서비스에는 죽은 무게라서
 * 스캔 범위를 market 로 한정하고 자체 최소 SecurityConfig 를 둔다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(MarketApplication.class, args);
    }
}
