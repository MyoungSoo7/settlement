package github.lms.lemuel.payment.adapter.out.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml} 의 {@code resilience4j.*} 설정이 <b>실제로 바인딩</b>되는지 못박는다.
 *
 * <p>왜 필요한가 — {@link CircuitBreakerRegistry#circuitBreaker(String)} 는 이름이 설정에 없으면
 * <i>기본값으로 새로 만들어서 돌려준다.</i> 즉 스타터가 바뀌어 프로퍼티가 안 붙어도 컨텍스트는
 * 멀쩡히 뜨고 PG 어댑터도 정상 동작하는 것처럼 보이는데, 서킷 임계치만 조용히 기본값
 * (창 100회·대기 60s)으로 바뀐다. 장애가 나야 알게 되는 종류의 회귀다.
 *
 * <p>이 프로젝트는 Spring Boot 4 라 스타터가 {@code resilience4j-spring-boot4} 여야 한다
 * (Boot 3 전용 모듈을 쓰면 2.4.0 부터 기동 자체가 막힌다). 모듈을 갈아끼울 때 프로퍼티
 * 프리픽스가 그대로인지 확인하는 것이 이 테스트의 목적이다.
 */
class PgResilienceConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class));

    @Test
    @DisplayName("PG 4종 서킷브레이커가 pgDefault 설정값으로 등록된다 (기본값으로 새로 만들어지지 않는다)")
    void circuitBreakersAreBoundFromYaml() {
        runner.run(ctx -> {
            CircuitBreakerRegistry registry = ctx.getBean(CircuitBreakerRegistry.class);

            for (String name : new String[] {"tossPg", "kcpPg", "nicePg", "inicisPg"}) {
                CircuitBreakerConfig config = registry.circuitBreaker(name).getCircuitBreakerConfig();

                assertThat(config.getSlidingWindowType())
                        .as("%s slidingWindowType", name)
                        .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
                assertThat(config.getSlidingWindowSize()).as("%s slidingWindowSize", name).isEqualTo(20);
                assertThat(config.getMinimumNumberOfCalls()).as("%s minimumNumberOfCalls", name).isEqualTo(10);
                assertThat(config.getFailureRateThreshold()).as("%s failureRateThreshold", name).isEqualTo(50f);
                assertThat(config.getPermittedNumberOfCallsInHalfOpenState())
                        .as("%s permittedNumberOfCallsInHalfOpenState", name).isEqualTo(5);
            }
        });
    }

    @Test
    @DisplayName("PG 4종 재시도가 pgDefault 설정값으로 등록된다")
    void retriesAreBoundFromYaml() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);

            for (String name : new String[] {"tossPg", "kcpPg", "nicePg", "inicisPg"}) {
                assertThat(registry.retry(name).getRetryConfig().getMaxAttempts())
                        .as("%s maxAttempts", name).isEqualTo(3);
            }
        });
    }

    @Test
    @DisplayName("PG 별 서킷은 서로 다른 인스턴스다 (한 PG 장애가 다른 PG 로 전이되지 않는다)")
    void eachPgHasItsOwnCircuit() {
        runner.run(ctx -> {
            CircuitBreakerRegistry registry = ctx.getBean(CircuitBreakerRegistry.class);
            assertThat(registry.circuitBreaker("tossPg"))
                    .isNotSameAs(registry.circuitBreaker("kcpPg"));
        });
    }

    @Test
    @DisplayName("설정에 없는 이름은 기본값으로 새로 만들어진다 — 위 단언이 무의미해지지 않는다는 확인")
    void unconfiguredNameFallsBackToDefaults() {
        runner.run(ctx -> {
            CircuitBreakerRegistry registry = ctx.getBean(CircuitBreakerRegistry.class);
            assertThat(registry.circuitBreaker("존재하지않는PG").getCircuitBreakerConfig()
                    .getWaitIntervalFunctionInOpenState().apply(1))
                    .isEqualTo(Duration.ofSeconds(60).toMillis());
        });
    }
}
