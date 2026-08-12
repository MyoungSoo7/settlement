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
 * 공제</b>한다 — payout 금액 = {@code immediate − withholding − offset}(T-4 로 차감 순서 확정). 과거엔 세무 전표(장부)만 원천징수를
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
    /** 원천징수 과소징수 발생 건수 — 현행 홀드백 정책(최대 30%)에서는 0 이어야 하며, 1 이상이면 세무 리스크 알람 대상. */
    private static final String METRIC_WITHHOLDING_SHORTFALL = "settlement.withholding.shortfall";

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
                // 즉시지급액 = net − 미해제 holdback − 원천징수(ADR 0029 §B) − 채권 상계(seed-p0-6).
                // 원천징수를 먼저 확보한 잔여에서 미상계 채권을 오래된 순으로 소진하고, 남은 금액만 Payout 으로 요청한다.
                // 0 이면 생성하지 않는다((정산, IMMEDIATE) 멱등, RequestPayoutUseCase 계약).
                BigDecimal immediate = saved.getImmediatePayoutAmount();

                WithholdingResolution withholding = resolveSettlementWithholdingUseCase.resolveForPayout(
                        sellerId, saved.getNetAmount());
                if (!withholding.profileRegistered()) {
                    // 세무 프로필 미등록 — 사업자 취급(원천징수 0, 전액 지급). 정책 근거는
                    // WithholdingResolution 문서 참조. 세무 리스크 감사 추적을 위해 반드시 로그를 남긴다.
                    log.warn("[SettlementConfirm] 세무 프로필 미등록 — 원천징수 미적용(사업자 취급). "
                            + "settlementId={}, sellerId={}", saved.getId(), sellerId);
                }

                // T-4 — 차감 순서: 원천징수 먼저, 채권 상계는 그 잔여까지만.
                // 근거는 "회수 가능성의 비대칭"이다. 못 뗀 채권은 OPEN 으로 남아 다음 정산에서 상계되지만
                // (seed-p0-6 이월 경로가 이미 있다), 못 뗀 원천징수는 이월 장치가 없어 그대로 소실된다
                // — 소실은 곧 과소징수(가산세 리스크)다. 회수가 지연될 뿐인 쪽을 뒤로 미루는 게 맞다.
                // 이 순서에서 원천징수는 사실상 항상 전액 확보된다: 등급 최대 홀드백이 30% 라
                // immediate ≥ 0.7 × net > 0.033 × net = 원천징수. 아래 min() 은 홀드백 정책이 96.7% 를
                // 넘는 미래에도 음수 지급이 나지 않게 하는 방어선이며, 걸리면 과소징수로 관측된다.
                BigDecimal effectiveWithholding = immediate.min(withholding.withholdingAmount());
                BigDecimal afterWithholding = immediate.subtract(effectiveWithholding);
                BigDecimal offset = offsetSellerRecoveryUseCase.offsetForConfirmedSettlement(
                        saved.getId(), sellerId, afterWithholding, saved.getSettlementDate());
                // 발행 이벤트(publishWithholdingAccrued)와 payout 감액이 반드시 동일한 effectiveWithholding 을
                // 써야 account GL 통제계정이 0 으로 닫힌다(GL 감사 HIGH #4 봉합).
                // max(0): 상계 서비스는 재실행 시 '넘긴 가용액'이 아니라 기존 상계 총액을 그대로 반환하므로,
                // 차감 순서가 바뀌기 전에 상계된 정산을 재확정하면 잔여를 초과할 수 있다. 음수 지급 요청은 금지다.
                BigDecimal payoutAmount = afterWithholding.subtract(offset).max(BigDecimal.ZERO);
                if (effectiveWithholding.compareTo(withholding.withholdingAmount()) < 0) {
                    meterRegistry.counter(METRIC_WITHHOLDING_SHORTFALL).increment();
                    log.error("[SettlementConfirm] 원천징수({})가 즉시지급액({})을 초과 — 실제 징수액({})으로 캡핑, "
                            + "차액은 미징수. settlementId={}, sellerId={}", withholding.withholdingAmount(),
                            immediate, effectiveWithholding, saved.getId(), sellerId);
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
