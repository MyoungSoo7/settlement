package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settlement service entry point.
 *
 * <p>이 서비스는 정산 도메인만 책임진다. 일/월 정산 생성·확정, 대사,
 * Kafka 결제 이벤트 컨슈머, ES 색인, PDF 생성을 담당한다.</p>
 *
 * <p>order-service 와 코드 의존성이 없다. 결제·주문·상품·유저 데이터는 settlement
 * 자체의 read-only projection ({@code settlement.adapter.out.readmodel}) 으로 읽는다.
 * 단일 PG 를 공유하지만 코드 경계는 완전히 분리.</p>
 */
@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.settlement",
        "github.lms.lemuel.report",
        "github.lms.lemuel.common",
    }
)
@EnableScheduling
public class SettlementServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        dotenv.entries().forEach(e ->
            System.setProperty(e.getKey(), e.getValue())
        );
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
