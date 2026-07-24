package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.in.ResolveSettlementWithholdingUseCase;
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
 *
 * <p><b>2026-07-24 정정(ADR 0029 §B, 독립 GL 감사 HIGH #4 봉합)</b>: 개인 셀러 원천징수를 <b>실제 지급액에서
 * 공제</b>한다 — payout 금액 = {@code immediate − offset − withholding}. 과거엔 세무 전표(장부)만 원천징수를
 * 줄이고 실제 송금은 net 전액이 나가던 결함이 있었다. 원천징수 확정 지점(= payout 산정 지점)에서
 * {@code lemuel.settlement.withholding_accrued} 를 발행해 account-service GL 이
 * {@code Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE} 로 폐루프를 닫게 한다(ADR 0026 확장).
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
    private final OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase;
    private final ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase;
    private final MeterRegistry meterRegistry;

    public SettlementConfirmItemWriter(SaveSettlementPort saveSettlementPort,
                                       LoadSellerIdPort loadSellerIdPort,
                                       PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                       EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                                       PublishSettlementEventPort publishSettlementEventPort,
                                       RequestPayoutUseCase requestPayoutUseCase,
                                       OffsetSellerRecoveryUseCase offsetSellerRecoveryUseCase,
                                       ResolveSettlementWithholdingUseCase resolveSettlementWithholdingUseCase,
                                       MeterRegistry meterRegistry) {
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.publishSettlementEventPort = publishSettlementEventPort;
        this.requestPayoutUseCase = requestPayoutUseCase;
        this.offsetSellerRecoveryUseCase = offsetSellerRecoveryUseCase;
        this.resolveSettlementWithholdingUseCase = resolveSettlementWithholdingUseCase;
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
                // 즉시지급액 = net − 미해제 holdback − 채권 상계(seed-p0-6) − 원천징수(ADR 0029 §B, HIGH #4 봉합).
                // 미상계 채권을 오래된 순으로 소진한 잔액에서 원천징수까지 뺀 최종액만 Payout 으로 요청한다.
                // 0 이면 생성하지 않는다((정산, IMMEDIATE) 멱등, RequestPayoutUseCase 계약).
                BigDecimal immediate = saved.getImmediatePayoutAmount();
                BigDecimal offset = offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(
                        saved.getId(), sellerId, immediate, saved.getSettlementDate());

                WithholdingResolution withholding = resolveSettlementWithholdingUseCase.resolveForPayout(
                        sellerId, saved.getNetAmount());
                if (!withholding.profileRegistered()) {
                    // 세무 프로필 미등록 — 사업자 취급(원천징수 0, 전액 지급). 정책 근거는
                    // WithholdingResolution 문서 참조. 세무 리스크 감사 추적을 위해 반드시 로그를 남긴다.
                    log.warn("[SettlementConfirm] 세무 프로필 미등록 — 원천징수 미적용(사업자 취급). "
                            + "settlementId={}, sellerId={}", saved.getId(), sellerId);
                }

                BigDecimal beforeWithholding = immediate.subtract(offset);
                // 원천징수는 실제 지급 가용액(beforeWithholding)을 넘을 수 없다 — 뗀 만큼만 GL 에 인식한다.
                // 발행 이벤트(publishWithholdingAccrued)와 payout 감액이 반드시 동일한 effectiveWithholding 을
                // 써야 account GL 통제계정이 0 으로 닫힌다(GL 감사 HIGH: 회수상계가 즉시분을 소진해 clamp 되면
                // 전액 원천징수를 발행하던 과대계상 결함 봉합). effectiveWithholding = min(withholding, 가용액).
                // 가용액이 원천징수 총액보다 작은 극단(회수상계 큼)에서 미징수 잔여분은 이 Seed 범위 밖(알려진 한계).
                BigDecimal effectiveWithholding = beforeWithholding.max(BigDecimal.ZERO)
                        .min(withholding.withholdingAmount());
                BigDecimal payoutAmount = beforeWithholding.subtract(effectiveWithholding);
                if (effectiveWithholding.compareTo(withholding.withholdingAmount()) < 0) {
                    log.warn("[SettlementConfirm] 원천징수({})가 즉시지급 가용액({})을 초과 — 실제 징수액({})으로 캡핑. "
                            + "settlementId={}, sellerId={}", withholding.withholdingAmount(), beforeWithholding,
                            effectiveWithholding, saved.getId(), sellerId);
                }

                if (effectiveWithholding.signum() > 0) {
                    publishSettlementDomainEventPort.publishWithholdingAccrued(
                            saved.getId(), sellerId, effectiveWithholding);
                }

                requestPayoutUseCase.requestPayoutOfType(
                        saved.getId(), sellerId, payoutAmount, PayoutType.IMMEDIATE);
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
