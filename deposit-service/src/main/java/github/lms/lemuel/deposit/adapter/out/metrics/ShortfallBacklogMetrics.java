package github.lms.lemuel.deposit.adapter.out.metrics;

import github.lms.lemuel.deposit.application.port.in.ManageShortfallUseCase;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 미해소 부족분 적체 지표.
 *
 * <p>부족분은 <b>자동으로 해소되지 않는다</b>(운영자 경로만 있다). 그래서 아무도 보지 않으면
 * 조용히 쌓인다 — 예외도 실패도 나지 않고, 잔고 불변식(total = available + locked)도 계속
 * 성립한다. 이 지표가 그 적체를 밖으로 꺼내는 유일한 창이다.
 *
 * <p>건수와 금액을 <b>둘 다</b> 낸다. 건수만 보면 1건짜리 대형 부족분이 100건짜리 소액 무리에
 * 묻히고, 금액만 보면 소액이 계속 늘어나는 추세(상류 계약이 어긋나는 신호)를 놓친다.
 *
 * <p>Gauge 를 폴링 콜백이 아니라 스냅샷 갱신으로 두는 이유: Gauge 콜백은 Prometheus 스크레이프
 * 시점마다 임의 스레드에서 실행되는데, 여기서 DB 를 읽으면 스크레이프가 DB 지연을 그대로 물고
 * 계좌 락과도 경합한다. 지표의 신선도(분 단위)가 그 위험을 감수할 만큼 중요하지 않다.
 */
@Component
public class ShortfallBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(ShortfallBacklogMetrics.class);

    private final ManageShortfallUseCase manageShortfallUseCase;
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicReference<BigDecimal> openAmount = new AtomicReference<>(BigDecimal.ZERO);

    @Value("${app.deposit.shortfall.alert-threshold:0}")
    private int alertThreshold;

    public ShortfallBacklogMetrics(ManageShortfallUseCase manageShortfallUseCase,
                                    MeterRegistry meterRegistry) {
        this.manageShortfallUseCase = manageShortfallUseCase;

        Gauge.builder("deposit.shortfall.open.count", openCount, AtomicInteger::doubleValue)
                .description("미해소(OPEN) 상계 부족분 건수")
                .register(meterRegistry);
        Gauge.builder("deposit.shortfall.open.amount", openAmount, ref -> ref.get().doubleValue())
                .description("미해소(OPEN) 상계 부족분 총액")
                .baseUnit("KRW")
                .register(meterRegistry);
    }

    /**
     * 5분마다 스냅샷 갱신. ShedLock 을 걸지 않는다 — 각 인스턴스가 자기 프로세스의 게이지를
     * 채워야 하고, 읽기 전용이라 중복 실행이 상태를 바꾸지 않는다.
     */
    @Scheduled(fixedDelayString = "${app.deposit.shortfall.metrics-interval-ms:300000}",
               initialDelayString = "${app.deposit.shortfall.metrics-initial-delay-ms:30000}")
    public void refresh() {
        try {
            List<DepositOffsetShortfall> open = manageShortfallUseCase.findOpenShortfalls();
            BigDecimal total = open.stream()
                    .map(DepositOffsetShortfall::getShortfallAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            openCount.set(open.size());
            openAmount.set(total);

            if (alertThreshold > 0 && open.size() >= alertThreshold) {
                log.warn("[deposit] 부족분 적체 {}건 총액 {} — 임계({}) 초과. /admin/deposits/shortfalls 확인 필요",
                        open.size(), total, alertThreshold);
            }
        } catch (RuntimeException e) {
            // 지표 갱신 실패로 서비스를 흔들지 않는다. 다만 게이지는 마지막 값에 머무르므로,
            // "적체 0으로 보이는데 사실은 못 읽은 것"을 구분할 수 있게 로그를 남긴다.
            log.error("[deposit] 부족분 지표 갱신 실패 — 게이지는 직전 값 유지", e);
        }
    }
}
