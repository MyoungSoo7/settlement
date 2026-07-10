package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * account-service(계정계) 독립 부팅 진입점.
 *
 * <p>★ 자체 DB(lemuel_account) 를 소유하는 DB-per-service 이며, loan·investment·settlement 이 발행하는
 * Kafka 이벤트를 소비해 전사 복식부기 GL 로 집계한다(발행 없음, 소비 전용).
 *
 * <p><b>제한 스캔(consume-only):</b> shared-common 의 JWT SecurityConfig·멱등 컨슈머 골격
 * (IdempotentEventConsumer/ProcessedEvent)·Jackson·감사 인프라는 그대로 쓰되, <em>Outbox 발행
 * 머신러리(publisher·scheduler·OutboxEventJpaEntity)</em>는 제외한다. 이 서비스는 이벤트를 발행하지 않아
 * {@code outbox_events} 테이블이 없으므로(ddl-auto=validate), 해당 엔티티/리포지토리가 스캔되면 부팅이
 * 깨진다. 따라서 component/entity/repository 스캔에서 {@code common.outbox.adapter.out} 과
 * {@code common.outbox.application} 을 명시적으로 배제하고, 필요한
 * {@code common.outbox.adapter.in.kafka}(ProcessedEvent) 와 {@code common.audit} 만 남긴다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "github.lms.lemuel",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = {
                        "github\\.lms\\.lemuel\\.common\\.outbox\\.adapter\\.out\\..*",
                        "github\\.lms\\.lemuel\\.common\\.outbox\\.adapter\\.in\\.web\\..*",  // OutboxAdminController — 배제된 OutboxAdminUseCase 를 요구
                        "github\\.lms\\.lemuel\\.common\\.outbox\\.application\\..*"
                }))
@EntityScan(basePackages = {
        "github.lms.lemuel.account.adapter.out.persistence",
        "github.lms.lemuel.common.outbox.adapter.in.kafka",      // ProcessedEventJpaEntity
        "github.lms.lemuel.common.audit.adapter.out.persistence" // AuditLogJpaEntity
})
@EnableJpaRepositories(basePackages = {
        "github.lms.lemuel.account.adapter.out.persistence",
        "github.lms.lemuel.common.outbox.adapter.in.kafka",      // ProcessedEventRepository
        "github.lms.lemuel.common.audit.adapter.out.persistence" // SpringDataAuditLogJpaRepository
})
@EnableScheduling
public class AccountServiceApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
