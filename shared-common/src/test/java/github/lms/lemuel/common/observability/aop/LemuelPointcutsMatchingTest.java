package github.lms.lemuel.common.observability.aop;

import github.lms.lemuel.tracefixture.adapter.in.batch.SampleBatchAdapter;
import github.lms.lemuel.tracefixture.application.service.SampleAppService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LemuelPointcuts#traceable()} 이 실제 패키지 컨벤션과 매칭되는지 검증한다.
 *
 * <p>{@link MethodTraceAspectTest} 는 포인트컷을 테스트 전용으로 재정의해 어드바이스 본문만
 * 검증하므로, 여기서는 재정의 없이 <b>실제 Aspect 그대로</b> 프록시를 만들어
 * 포인트컷 표현식 자체가 각 레이어 픽스처에 적용되는지 본다.
 */
class LemuelPointcutsMatchingTest {

    private <T> T realAspectProxy(T target, MeterRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new MethodTraceAspect(new ObservabilityAopProperties(), provider));
        return factory.getProxy();
    }

    @Test
    void batch_adapter_is_traceable_with_batch_layer_tag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleBatchAdapter proxy = realAspectProxy(new SampleBatchAdapter(), registry);

        assertThat(proxy.runJob()).isEqualTo("done");

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "batch")
                .tag("class", "SampleBatchAdapter")
                .tag("method", "runJob")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("adapter.in.batch 스케줄러/폴러가 traceable() 에 포함되어야 한다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void application_service_stays_traceable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SampleAppService proxy = realAspectProxy(new SampleAppService(), registry);

        assertThat(proxy.handle()).isEqualTo("handled");

        Timer timer = registry.find("lemuel.method.execution")
                .tag("layer", "service")
                .tag("class", "SampleAppService")
                .tag("outcome", "success")
                .timer();
        assertThat(timer)
                .as("기존 application.service 매칭이 회귀하면 안 된다")
                .isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
