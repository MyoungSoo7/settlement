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
 * <p><b>컨테이너 기동 시점</b>도 중요하다. 이 클래스의 {@code static} 초기화 블록에서 바로 띄우면
 * 안 된다 — JUnit 은 탐색(discovery) 단계에서 {@code Class.forName(name, true, loader)} 로 테스트
 * 클래스를 <em>초기화까지</em> 하며 로드한다. 즉 컨테이너 기동 로그(약 12KB)가 "어떤 테스트 클래스도
 * 시작되지 않은" 시점에 stdout 으로 쏟아지고, Gradle 은 그 출력을 귀속시킬 클래스 구간을 아직 열지
 * 않은 상태라 {@code TestOutputStore} 인덱스가 실제 {@code output.bin} 과 어긋난다
 * ({@code Could not write XML test results ... EOFException}).
 *
 * <p>그래서 컨테이너는 아래 {@link Containers} 홀더에 담아 <b>지연 초기화</b>한다. 홀더는
 * {@link #datasourceProperties} 가 처음 참조할 때 초기화되고, 그 메서드는 스프링 컨텍스트가 뜨는
 * 시점 = 이미 테스트 실행이 시작된 뒤에 호출된다. 따라서 기동 로그가 정상적으로 해당 테스트 클래스
 * 구간에 귀속된다.
 *
 * <p>컨테이너는 한 번 띄운 뒤 명시적으로 내리지 않는다(JVM 종료
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
                "app.outbox.polling-delay-ms=3600000",

                // 배너는 로깅 프레임워크가 아니라 System.out 으로 직접 나가므로
                // logback-test.xml 의 루트 WARN 으로 잡히지 않는다. 여기서 끈다.
                "spring.main.banner-mode=off"
                // 나머지 콘솔 출력 억제는 src/test/resources/logback-test.xml 의 루트 WARN 이
                // 단일 창구다(JVM 종료 훅이 뿜는 EntityManagerFactory·Hikari 종료 INFO 포함).
        }
)
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
abstract class InsuranceIntegrationTestSupport {

    /**
     * 지연 초기화 홀더 — 이 클래스가 처음 참조되는 순간(=@DynamicPropertySource 호출 시점,
     * 테스트 실행 중)에만 컨테이너가 뜬다. JUnit 탐색 단계의 클래스 초기화에 휩쓸리지 않는다.
     */
    private static final class Containers {

        static final PostgreSQLContainer<?> POSTGRES = startPostgres();

        private static PostgreSQLContainer<?> startPostgres() {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("insurance_test")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();   // 의도적으로 stop() 하지 않는다 — JVM 종료 시 Ryuk 이 회수한다.
            return postgres;
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
        PostgreSQLContainer<?> postgres = Containers.POSTGRES;
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("POSTGRES_USER", postgres::getUsername);
        r.add("POSTGRES_PASSWORD", postgres::getPassword);
    }
}
