package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadReleasableHoldbackPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
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
    private final LoadSellerIdPort loadSellerIdPort;
    private final RequestPayoutUseCase requestPayoutUseCase;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    private final Counter releasedCounter;

    public ReleaseHoldbackService(LoadReleasableHoldbackPort loadPort,
                                   SaveSettlementPort savePort,
                                   LoadSellerIdPort loadSellerIdPort,
                                   RequestPayoutUseCase requestPayoutUseCase,
                                   PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                   MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.requestPayoutUseCase = requestPayoutUseCase;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
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
                // 해제 시점의 잔여 보류액(환불·차지백·PG 대사로 소비되지 않은 분)을 지급 대상으로 못박는다.
                BigDecimal amount = s.getHoldbackAmount();
                s.releaseHoldback(today);
                savePort.save(s);
                // 잔여 보류액을 HOLDBACK_RELEASE Payout 으로 자동 생성 — 판매자 미해석·0원이면 생략.
                // 같은 트랜잭션에 묶여 해제와 원자적으로 커밋된다((정산, HOLDBACK_RELEASE) 멱등).
                loadSellerIdPort.findSellerIdByPaymentId(s.getPaymentId()).ifPresent(sellerId -> {
                    requestPayoutUseCase.requestPayoutOfType(
                            s.getId(), sellerId, amount, PayoutType.HOLDBACK_RELEASE);
                    // account 로 유보 해제 재분류 이벤트 발행 — 0원 유보는 회계 전기가 없으므로 생략.
                    if (amount.signum() > 0) {
                        publishSettlementDomainEventPort.publishHoldbackReleased(s.getId(), sellerId, amount);
                    }
                });
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
