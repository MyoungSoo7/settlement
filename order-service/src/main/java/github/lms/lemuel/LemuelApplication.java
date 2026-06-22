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
        "github.lms.lemuel.report",
        "github.lms.lemuel.game",
        "github.lms.lemuel.projectionbackfill",
        "github.lms.lemuel.recon",
        "github.lms.lemuel.common",
        // ADR 0020 Phase 5.5 — settlement 분리 완료. settlement/ledger/payout/chargeback/
        // pgreconciliation 코드는 settlement-service 로 이전돼 order 소스에 존재하지 않으므로
        // 과거의 번들 스캔 엔트리(아무 빈도 못 잡던 잔재)를 제거했다. opslab 의 잔여 테이블 정리는
        // docs/runbook/settlement-db-decommission.md 참조.
        // reservation 도 reservation-service(독립 MSA, :8083, gateway 라우팅)가 소유한다. order 소스에
        // 레거시 reservation 패키지가 남아있으나 스캔에서 제외한다 — 컴포넌트(Adapter)는 스캔되는데
        // JPA 리포지토리는 PersistenceConfig 의 @EnableJpaRepositories 스코프 밖이라 빈 미생성 →
        // fresh 부팅이 깨지던 문제를 스캔 제외로 해소.
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
