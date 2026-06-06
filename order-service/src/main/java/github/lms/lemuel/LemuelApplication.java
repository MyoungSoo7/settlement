package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.user",
        "github.lms.lemuel.order",
        "github.lms.lemuel.cart",
        "github.lms.lemuel.shipping",
        "github.lms.lemuel.payment",
        "github.lms.lemuel.product",
        "github.lms.lemuel.category",
        "github.lms.lemuel.coupon",
        "github.lms.lemuel.review",
        "github.lms.lemuel.report",
        "github.lms.lemuel.game",
        "github.lms.lemuel.common",
        // settlement-service 모듈 (임시 번들). ledger 테이블(ledger_entries V45, ledger_outbox V49)이
        // 적용돼 ledger 포함 — settlement 서비스가 EnqueueLedgerTaskPort 빈을 필요로 한다.
        "github.lms.lemuel.settlement",
        "github.lms.lemuel.ledger",
        "github.lms.lemuel.pgreconciliation",
        "github.lms.lemuel.payout",
        "github.lms.lemuel.chargeback",
    }
)
@EntityScan(basePackages = {
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.chargeback",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.ledger",
    "github.lms.lemuel.order",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.payout",
    "github.lms.lemuel.pgreconciliation",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
})
@EnableJpaRepositories(basePackages = {
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.chargeback",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.ledger",
    "github.lms.lemuel.order",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.payout",
    "github.lms.lemuel.pgreconciliation",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
})
@EnableScheduling
public class LemuelApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        dotenv.entries().forEach(e ->
            System.setProperty(e.getKey(), e.getValue())
        );
        SpringApplication.run(LemuelApplication.class, args);
    }

}
