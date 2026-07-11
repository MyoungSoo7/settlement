package github.lms.lemuel.common.observability.aop;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MethodTraceAspect} 가 대상 메서드를 감싸 실행시간을 재고 Micrometer Timer 에
 * 성공/실패 결과를 기록하는지 검증한다. Spring 컨텍스트 없이 AspectJ 프록시로 단위 검증.
 */
class MethodTraceAspectTest {

    // DEBUG 를 켜야 진입/종료 debug 로그 + argsSuffix/renderArg 경로가 실행된다.
    private static Level originalLevel;

    @BeforeAll
    static void enableDebug() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
    }

    @AfterAll
    static void restoreLevel() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MethodTraceAspect.class)).setLevel(originalLevel);
    }

    /** 포인트컷(applicationService) 매칭을 위한 가짜 서비스 — 패키지 컨벤션을 모사. */
    static class SampleService {
        String greet(String name) {
            return "hi " + name;
        }

        void boom() {
            throw new IllegalStateException("kaboom");
        }

        String ping() {
            return "pong";
        }
    }

    private SampleService proxyWith(MeterRegistry registry) {
        return proxyWith(registry, new ObservabilityAopProperties());
    }

    private SampleService proxyWith(MeterRegistry registry, ObservabilityAopProperties props) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(new TestAspect(props, provider));
        return factory.getProxy();
    }

    /** SampleService 를 직접 겨냥하는 포인트컷으로 재정의한 테스트 전용 Aspect. */
    @org.aspectj.lang.annotation.Aspect
    static class TestAspect extends MethodTraceAspect {
        TestAspect(ObservabilityAopProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
            super(properties, meterRegistry);
        }

        @org.aspectj.lang.annotation.Around(
                "execution(* github.lms.lemuel.common.observability.aop.MethodTraceAspectTest.SampleService.*(..))")
        public Object around(org.aspectj.lang.ProceedingJoinPoint pjp) throws Throwable {
            return trace(pjp);
        }
    }

    @Test
    void records_success_timer_and_returns_result() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleService service = proxyWith(registry);

        String result = service.greet("lemuel");

        assertThat(result).isEqualTo("hi lemuel");
        Timer timer = registry.find("lemuel.method.execution")
                .tag("method", "greet")
                .tag("outcome", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void records_error_timer_and_rethrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleService service = proxyWith(registry);

        assertThatThrownBy(service::boom).isInstanceOf(IllegalStateException.class);

        Timer timer = registry.find("lemuel.method.execution")
                .tag("method", "boom")
                .tag("outcome", "error")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void works_without_meter_registry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(new TestAspect(new ObservabilityAopProperties(), provider));
        SampleService service = factory.getProxy();

        // MeterRegistry 가 없어도 비즈니스 흐름은 정상 동작해야 한다.
        assertThat(Stream.of(service.greet("x")).findFirst()).contains("hi x");
    }

    @Test
    void slow_threshold_promotes_to_warn() {
        ObservabilityAopProperties props = new ObservabilityAopProperties();
        props.setSlowThresholdMs(0); // 모든 호출을 SLOW 로 간주 → WARN 승격 분기 커버
        SampleService service = proxyWith(new SimpleMeterRegistry(), props);

        assertThat(service.greet("slow")).isEqualTo("hi slow");
    }

    @Test
    void renders_args_when_logArgs_enabled() {
        ObservabilityAopProperties props = new ObservabilityAopProperties();
        props.setLogArgs(true);
        props.setMaxArgLength(2); // 인자 길이 초과 절단 분기 커버
        SampleService service = proxyWith(new SimpleMeterRegistry(), props);

        // 인자 있는 호출(절단) + 인자 없는 호출(빈 괄호) 두 분기
        assertThat(service.greet("abcdef")).isEqualTo("hi abcdef");
        assertThat(service.ping()).isEqualTo("pong");
    }
}
