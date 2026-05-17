package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
        // settlement-service 모듈 (임시 번들)
        "github.lms.lemuel.settlement",
        "github.lms.lemuel.pgreconciliation",
        "github.lms.lemuel.payout",
        "github.lms.lemuel.chargeback",
        "github.lms.lemuel.ledger",
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
