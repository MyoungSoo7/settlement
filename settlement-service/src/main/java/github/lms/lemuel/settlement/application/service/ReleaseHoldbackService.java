package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadReleasableHoldbackPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 보류 해제 서비스 — release_date 도달한 정산의 holdback 을 풀어준다.
 *
 * <p>실 운영: HoldbackReleaseScheduler 가 매일 새벽 호출. 한 번에 100 건씩 페이지 처리하여
 * 락 경합 최소화.
 */
@Service
@Transactional
public class ReleaseHoldbackService implements ReleaseHoldbackUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseHoldbackService.class);
    private static final int BATCH_SIZE = 100;

    private final LoadReleasableHoldbackPort loadPort;
    private final SaveSettlementPort savePort;
    private final Counter releasedCounter;

    public ReleaseHoldbackService(LoadReleasableHoldbackPort loadPort,
                                   SaveSettlementPort savePort,
                                   MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.releasedCounter = Counter.builder("settlement.holdback.released")
                .description("Holdback 해제된 누적 정산 건수")
                .register(meterRegistry);
    }

    @Override
    public int releaseAllDueOn(LocalDate today) {
        int totalReleased = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        while (true) {
            List<Settlement> batch = loadPort.findReleasableOn(today, BATCH_SIZE);
            if (batch.isEmpty()) break;

            for (Settlement s : batch) {
                BigDecimal amount = s.getHoldbackAmount();
                s.releaseHoldback(today);
                savePort.save(s);
                totalAmount = totalAmount.add(amount);
                totalReleased++;
            }
            log.info("Holdback release batch: {} settlements, totalAmount={}",
                    batch.size(), totalAmount);
            if (batch.size() < BATCH_SIZE) break;
        }
        if (totalReleased > 0) {
            releasedCounter.increment(totalReleased);
            log.info("Holdback release done. count={}, totalAmount={}, today={}",
                    totalReleased, totalAmount, today);
        }
        return totalReleased;
    }
}
