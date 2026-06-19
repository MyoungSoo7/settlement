package github.lms.lemuel.reservation;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * reservation-service 독립 실행 부트스트랩 (Phase B).
 *
 * <p>order-service 와 분리 배포되며 자체 DB(reservations_db)·자체 포트(8083)를 갖는다.
 * common 패키지를 함께 스캔해 shared-common 의 JWT 필터·SecurityConfig·Outbox 인프라를 사용한다.
 * user/order/product 등 타 도메인 패키지는 스캔하지 않는다 — MSA 코드 경계 0.
 */
@SpringBootApplication(scanBasePackages = {
        "github.lms.lemuel.reservation",
        // shared-common 에서 인증(JWT/SecurityConfig)만 선택 스캔한다.
        //  - common.outbox 제외: OutboxPublisherScheduler 등 무조건 빈이 outbox_events 테이블을 요구
        //    (reservation 은 발행 안 하고 consume 만 — 자체 processed_events 보유)
        //  - common.audit 제외: AuditLogPersistenceAdapter 가 audit_log JPA 리포지토리/테이블을 요구
        //  - Kafka 컨테이너 팩토리는 자체 ReservationKafkaConfig, 예외 매핑은 ReservationExceptionHandler 가 담당
        "github.lms.lemuel.common.config.jwt",
})
// shared-common 의 Jackson 2↔3 호환 ObjectMapper 빈만 선택 주입한다.
// (common.config 전체 스캔은 Redis 의존 TwoTierCacheConfig 등이 딸려와 회피)
@Import(JacksonCompatConfig.class)
@EnableScheduling
public class ReservationServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(ReservationServiceApplication.class, args);
    }
}
