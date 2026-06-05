package github.lms.lemuel.common.config.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock 활성화 — 같은 @Scheduled 메서드가 replicas N 개 중 *1 개* 만 실행되게 한다.
 *
 * <p>현재 prod 는 모두 replicas: 1 이라 사실상 *미래 HA 대비* 의 사전 도입. ArgoCD 의 rolling
 * update 중 옛/새 pod 가 *0.5 초 가량 공존* 하는 순간의 중복 발행도 차단.
 *
 * <p>{@code @SchedulerLock} 이 붙은 메서드는 *동일 name 으로 DB row 락* 을 잡으며, {@code
 * lockAtMostFor} 이내에 락을 해제하지 못해도 *시간 만료* 시 자동 해제 (deadlock 방지).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingLockConfig {

    @Bean
    public LockProvider shedLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime() // DB time 사용 → 노드 간 clock drift 면역
                        .build());
    }
}
