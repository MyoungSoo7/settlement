package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.user",
        "github.lms.lemuel.order",
        "github.lms.lemuel.payment",
        "github.lms.lemuel.product",
        "github.lms.lemuel.category",
        "github.lms.lemuel.coupon",
        "github.lms.lemuel.review",
        "github.lms.lemuel.report",
        "github.lms.lemuel.game",
        "github.lms.lemuel.common",
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
