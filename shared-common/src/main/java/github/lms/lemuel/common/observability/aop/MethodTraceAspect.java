package github.lms.lemuel.common.observability.aop;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 로깅 + 성능 추적을 담당하는 핵심 Aspect.
 *
 * <p>{@link LemuelPointcuts#traceable()} (웹 어댑터 · 애플리케이션 서비스 · Kafka 컨슈머) 에 대해:
 * <ul>
 *   <li>진입 시 DEBUG 로그 ({@code → Layer Class.method})</li>
 *   <li>실행 시간 측정 후 종료 로그 ({@code ← ... (12ms)})</li>
 *   <li>{@code slow-threshold-ms} 초과 시 WARN 으로 승격</li>
 *   <li>예외 발생 시 ERROR 로그 + 소요시간</li>
 *   <li>Micrometer {@code lemuel.method.execution} Timer 에 layer/class/method/outcome 태그로 기록</li>
 * </ul>
 *
 * <p>가장 바깥에서 시간을 재야 내부(트랜잭션 포함) 전체가 포착되므로
 * {@link Order} 를 최우선으로 둔다. {@link AuditAspect}(LOWEST_PRECEDENCE-100) 보다 바깥.
 *
 * <p>MDC 의 traceId 는 {@code TraceIdFilter} 가 이미 부착하므로 로그 한 줄로 호출 체인이 연결된다.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MethodTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(MethodTraceAspect.class);
    private static final String TIMER_NAME = "lemuel.method.execution";

    private final ObservabilityAopProperties properties;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public MethodTraceAspect(ObservabilityAopProperties properties,
                             ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Around("github.lms.lemuel.common.observability.aop.LemuelPointcuts.traceable()")
    public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String layer = layerOf(signature.getDeclaringType().getName());
        String type = signature.getDeclaringType().getSimpleName();
        String method = signature.getName();

        if (log.isDebugEnabled()) {
            log.debug("→ [{}] {}.{}{}", layer, type, method, argsSuffix(joinPoint));
        }

        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Throwable error) {
            outcome = "error";
            long elapsedMs = elapsedMs(startNanos);
            log.error("✗ [{}] {}.{} failed after {}ms — {}: {}",
                    layer, type, method, elapsedMs,
                    error.getClass().getSimpleName(), error.getMessage());
            throw error;
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            record(layer, type, method, outcome, elapsedNanos);

            if ("success".equals(outcome)) {
                if (elapsedMs >= properties.getSlowThresholdMs()) {
                    log.warn("← [{}] {}.{} SLOW {}ms (threshold {}ms)",
                            layer, type, method, elapsedMs, properties.getSlowThresholdMs());
                } else if (log.isDebugEnabled()) {
                    log.debug("← [{}] {}.{} {}ms", layer, type, method, elapsedMs);
                }
            }
        }
    }

    private void record(String layer, String type, String method, String outcome, long elapsedNanos) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            Timer.builder(TIMER_NAME)
                    .tag("layer", layer)
                    .tag("class", type)
                    .tag("method", method)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // 메트릭 기록 실패가 비즈니스 흐름을 막아선 안 된다.
            log.debug("Failed to record method timer for {}.{}", type, method, e);
        }
    }

    private static String layerOf(String declaringClassName) {
        if (declaringClassName.contains(".adapter.in.web")) {
            return "web";
        }
        if (declaringClassName.contains(".adapter.in.kafka")) {
            return "kafka";
        }
        if (declaringClassName.contains(".application.service")) {
            return "service";
        }
        return "other";
    }

    private String argsSuffix(ProceedingJoinPoint joinPoint) {
        if (!properties.isLogArgs()) {
            return "";
        }
        Object[] args = joinPoint.getArgs();
        if (args.length == 0) {
            return "()";
        }
        String rendered = Arrays.stream(args)
                .map(this::renderArg)
                .collect(Collectors.joining(", ", "(", ")"));
        return rendered;
    }

    private String renderArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        String value;
        try {
            value = String.valueOf(arg);
        } catch (RuntimeException e) {
            return arg.getClass().getSimpleName() + "@?";
        }
        int max = properties.getMaxArgLength();
        if (value.length() > max) {
            return value.substring(0, max) + "…";
        }
        return value;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
