package github.lms.lemuel;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 테스트 전용 Spring Boot 부트스트랩.
 *
 * <p>운영에서 settlement-service 는 <b>라이브러리 모드</b>로 order-service 의 fat jar 에 번들되며
 * (build.gradle.kts: bootJar disabled), 독립 {@code @SpringBootApplication} 을 갖지 않는다.
 * 그래서 settlement-service 패키지만 격리해 {@code @SpringBootTest} 로 띄우려면 테스트 소스셋에
 * 부트스트랩 클래스가 필요하다 — 이 클래스가 그 역할이며 <b>운영 빌드에는 포함되지 않는다</b>.
 *
 * <p>{@code github.lms.lemuel} 루트에서 컴포넌트 스캔하므로 settlement-service main +
 * shared-common 빈만 잡힌다 (order-service 는 테스트 클래스패스에 없어 MSA 경계 유지).
 */
@SpringBootApplication
public class SettlementServiceApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(SettlementServiceApplication.class, args);
    }
}
