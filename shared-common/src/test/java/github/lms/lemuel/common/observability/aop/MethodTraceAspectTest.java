package github.lms.lemuel.common.observability.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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

    /** 포인트컷(applicationService) 매칭을 위한 가짜 서비스 — 패키지 컨벤션을 모사. */
    static class SampleService {
        String greet(String name) {
            return "hi " + name;
        }

        void boom() {
            throw new IllegalStateException("kaboom");
        }
    }

    private SampleService proxyWith(MeterRegistry registry) {
        ObservabilityAopProperties props = new ObservabilityAopProperties();
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
}
