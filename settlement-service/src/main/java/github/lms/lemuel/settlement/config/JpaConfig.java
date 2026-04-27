package github.lms.lemuel.settlement.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA scanning은 별도 Configuration 클래스로 분리.
 *
 * <p>{@link github.lms.lemuel.SettlementServiceApplication} 에 둘 경우
 * {@code @WebMvcTest} 같은 슬라이스 테스트가 메인 앱을 진입점으로 사용하면서
 * JPA 메타데이터까지 로드하려 해 entityManagerFactory 빈 조회 실패가 난다.
 * 별도 Config 로 분리하면 슬라이스 테스트는 이 클래스를 import 하지 않으므로 안전.</p>
 */
@Configuration
@EntityScan(basePackages = {
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.common.audit.adapter.out.persistence",
    "github.lms.lemuel.common.outbox.adapter.in.kafka",
})
@EnableJpaRepositories(basePackages = {
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.common.audit.adapter.out.persistence",
    "github.lms.lemuel.common.outbox.adapter.in.kafka",
})
public class JpaConfig {
}
