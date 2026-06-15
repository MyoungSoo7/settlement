package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.config",
        "github.lms.lemuel.user",
        "github.lms.lemuel.order",
        "github.lms.lemuel.cart",
        "github.lms.lemuel.shipping",
        "github.lms.lemuel.payment",
        "github.lms.lemuel.product",
        "github.lms.lemuel.category",
        "github.lms.lemuel.coupon",
        "github.lms.lemuel.review",
        "github.lms.lemuel.reservation",
        "github.lms.lemuel.reservation_bridge",
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
