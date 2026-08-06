package github.lms.lemuel.insurance.integration;

import github.lms.lemuel.InsuranceServiceApplication;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * insurance-service 통합테스트 공통 기반 — <b>싱글턴 컨테이너</b> 패턴.
 *
 * <p>Testcontainers 공식 "Singleton containers" 권장 패턴이다. {@code @Testcontainers} +
 * {@code @Container} 를 클래스마다 두면 통합테스트 클래스 수만큼 PostgreSQL 컨테이너와
 * Spring 컨텍스트가 생겼다가 각각 {@code afterAll} 에서 순차적으로 내려간다. 그러면 한 테스트
 * 워커 JVM 안에서 이미 종료된 컨텍스트/커넥션풀이 <em>뒤늦게</em> stdout 을 뿜고, 그 출력이
 * "이미 종료 처리된 테스트 클래스"에 귀속되면서 Gradle {@code TestOutputStore} 인덱스가 어긋나
 * {@code Could not write XML test results ... Buffer underflow} 로 test 태스크가 통째로 실패했다.
 *
 * <p>여기서는 컨테이너를 static 초기화 블록에서 한 번만 띄우고 명시적으로 내리지 않는다(JVM 종료
 * 시 Testcontainers Ryuk 이 정리). 모든 통합테스트가 이 클래스를 상속하면
 * {@code @SpringBootTest} 속성·{@code @DynamicPropertySource} 기여자가 동일해져 스프링 테스트
 * 컨텍스트 캐시 키도 하나로 합쳐진다 → <b>컨텍스트 1개 · 컨테이너 1개</b>. 부수적으로 통합테스트
 * 전체 실행 시간도 크게 줄어든다.
 *
 * <p>DB 를 공유하지만 각 테스트가 자기 UUID 로만 행을 만들고 검증하므로 상호 간섭은 없다.
 */
@SpringBootTest(
        classes = InsuranceServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                // Outbox 폴러(shared-common, 기본 2초 fixedDelay)를 사실상 정지시킨다.
                // 이 테스트들은 폴러를 검증하지 않는데, 배경 스레드가 계속 DB 를 때리며 뿜는 로그가
                // 위에 적은 TestOutputStore 손상 위험을 키운다. 폴러 빈 자체는 그대로 생성되므로
                // "부팅 성공" 검증 취지는 유지된다.
                "app.outbox.polling-delay-ms=3600000"
        }
)
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
abstract class InsuranceIntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("insurance_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        if (isDockerAvailable()) {
            POSTGRES.start();   // 의도적으로 stop() 하지 않는다 — JVM 종료 시 Ryuk 이 회수한다.
        }
    }

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }
}
