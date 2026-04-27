package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Settlement service entry point.
 *
 * <p>이 서비스는 정산 도메인만 책임진다. 일/월 정산 생성·확정, 대사,
 * Kafka 결제 이벤트 컨슈머, ES 색인, PDF 생성을 담당한다.</p>
 *
 * <p><b>임시 cross-module 의존성</b>: Phase 5 에서 Kafka 이벤트 + 자체 read model 로
 * 끊어내기 전까지 order-service 의 JpaEntity (Order/Payment/Product/User) 를
 * 직접 참조한다. 그래서 EntityScan / EnableJpaRepositories 가 그 패키지들도 포함.</p>
 */
@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.settlement",
        "github.lms.lemuel.common",
    }
)
@EntityScan(basePackages = {
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.payment.adapter.out.persistence",
    "github.lms.lemuel.order.adapter.out.persistence",
    "github.lms.lemuel.product.adapter.out.persistence",
    "github.lms.lemuel.user.adapter.out.persistence",
    "github.lms.lemuel.common.audit.adapter.out.persistence",
})
@EnableJpaRepositories(basePackages = {
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.payment.adapter.out.persistence",
    "github.lms.lemuel.order.adapter.out.persistence",
})
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
