package github.lms.lemuel;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

/**
 * settlement-service 독립 실행 진입점 (ADR 0020 Phase 0 — standalone 승격).
 *
 * <p>이전에는 라이브러리 모드로 order-service fat jar 에 번들됐으나, 자체 실행가능 jar 로 독립
 * 기동(:8082)하도록 승격했다. {@code github.lms.lemuel} 루트에서 컴포넌트 스캔하므로
 * settlement-service main + shared-common 빈만 잡힌다 (order-service 는 이 모듈의 classpath 에
 * 없어 MSA 코드 경계가 그대로 유지된다).
 *
 * <p>Phase 0 범위: 프로세스만 독립, DB 는 여전히 공유 {@code opslab} (물리 분리는 Phase 4).
 * 그래서 {@code spring.flyway.enabled=false} (스키마는 order-service 가 소유), JPA 는 validate.
 */
@SpringBootApplication
public class SettlementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
