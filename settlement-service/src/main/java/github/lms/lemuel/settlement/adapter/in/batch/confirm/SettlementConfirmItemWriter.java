package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 정산 확정 청크 배치의 라이터 — 청크 단위로 확정 정산을 저장하고 후속 이벤트를 발행한다.
 *
 * <p>저장 · loan SettlementConfirmed 발행 · 원장 분개 아웃박스 적재 · ES 인덱싱 이벤트가 모두
 * 청크 트랜잭션과 같은 커밋에 묶인다(아웃박스 패턴 → 크래시 일관성). 하루치 전체를 단일 트랜잭션으로
 * 처리하던 기존 구조와 달리, 청크마다 커밋해 롱 트랜잭션·락 보유 시간을 제한한다.
 */
@Slf4j
@Component
public class SettlementConfirmItemWriter implements ItemWriter<Settlement> {

    /** 확정 정산 누적 건수. */
    private static final String METRIC_CONFIRMED_COUNT = "settlement.confirmed.count";
    /** 확정 정산 net_amount 누적 합(관측용 double — 회계값 아님). */
    private static final String METRIC_CONFIRMED_AMOUNT = "settlement.confirmed.amount";

    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    private final PublishSettlementEventPort publishSettlementEventPort;
    private final RequestPayoutUseCase requestPayoutUseCase;
    private final MeterRegistry meterRegistry;

    public SettlementConfirmItemWriter(SaveSettlementPort saveSettlementPort,
                                       LoadSellerIdPort loadSellerIdPort,
                                       PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                       EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                                       PublishSettlementEventPort publishSettlementEventPort,
                                       RequestPayoutUseCase requestPayoutUseCase,
                                       MeterRegistry meterRegistry) {
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.publishSettlementEventPort = publishSettlementEventPort;
        this.requestPayoutUseCase = requestPayoutUseCase;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        List<Long> confirmedIds = new ArrayList<>(chunk.size());
        BigDecimal confirmedNet = BigDecimal.ZERO;

        for (Settlement settlement : chunk) {
            Settlement saved = saveSettlementPort.save(settlement);
            confirmedIds.add(saved.getId());
            confirmedNet = confirmedNet.add(saved.getNetAmount());

            // loan-service 로 SettlementConfirmed 발행(상환 차감 트리거) + 즉시지급 Payout 자동 생성.
            // 판매자 미해석은 둘 다 생략. 같은 청크 트랜잭션에 묶여 원자적으로 커밋된다.
            loadSellerIdPort.findSellerIdByPaymentId(saved.getPaymentId()).ifPresent(sellerId -> {
                publishSettlementDomainEventPort.publishSettlementConfirmed(
                        saved.getId(), sellerId, saved.getNetAmount());
                // 즉시지급액 = net − 미해제 holdback. 0 이면 생성하지 않는다((정산, IMMEDIATE) 멱등).
                requestPayoutUseCase.requestPayoutOfType(
                        saved.getId(), sellerId, saved.getImmediatePayoutAmount(), PayoutType.IMMEDIATE);
            });
        }

        if (!confirmedIds.isEmpty()) {
            // 원장 분개 작업을 같은 트랜잭션에 아웃박스로 적재(크래시 내성) + ES 인덱싱 이벤트
            enqueueLedgerTaskPort.enqueueCreate(confirmedIds);
            publishSettlementEventPort.publishSettlementConfirmedEvent(confirmedIds);
            // 확정 처리량·금액을 메트릭으로 노출(정산 성공 지표 — 기존엔 log.info 뿐).
            meterRegistry.counter(METRIC_CONFIRMED_COUNT).increment(confirmedIds.size());
            meterRegistry.counter(METRIC_CONFIRMED_AMOUNT).increment(confirmedNet.doubleValue());
            log.info("정산 확정 청크 처리: confirmed={}, net합={}", confirmedIds.size(), confirmedNet);
        }
    }
}
