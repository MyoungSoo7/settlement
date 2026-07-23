package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * 환불 발생 시 정산 조정 서비스.
 *
 * <p><b>지급후 회수 채권(seed-p0-6) 범위 밖</b>: 채권 발생 원천은 차지백·PG 대사 회수로 한정된다
 * (시드 제약). 구매자 환불이 DONE·지급완료 정산에 닿는 경우는 여기서 채권을 만들지 않는다 —
 * 그 비대칭이 필요해지면 {@code RecordPostPayoutRecoveryUseCase} 배선을 이 서비스로 확장한다.
 */
@Service
@Transactional
public class AdjustSettlementForRefundService implements AdjustSettlementForRefundUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdjustSettlementForRefundService.class);

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    /** KST 기준 시각 소스 — 조정/역분개 기준일이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public AdjustSettlementForRefundService(LoadSettlementPort loadSettlementPort,
                                            SaveSettlementPort saveSettlementPort,
                                            SaveSettlementAdjustmentPort saveSettlementAdjustmentPort,
                                            EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                                            LoadSellerIdPort loadSellerIdPort,
                                            PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                            Clock clock) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.saveSettlementAdjustmentPort = saveSettlementAdjustmentPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
        this.clock = clock;
    }

    @Override
    public Settlement adjustSettlementForRefund(Long paymentId, BigDecimal refundAmount, Long refundId) {
        log.info("Adjusting settlement for refund. paymentId={}, refundAmount={}, refundId={}",
                paymentId, refundAmount, refundId);

        // 해당 결제의 정산을 비관적 락(SELECT ... FOR UPDATE)으로 조회.
        // 동시 환불 2건이 같은 refundedAmount/holdback 을 읽고 각자 차감해 덮어쓰는 lost update 방지.
        Settlement settlement = loadSettlementPort.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException("Settlement not found for paymentId: " + paymentId));

        // ADR 0026 Option ① 상한(HIGH-A, GL 재감사 발견) — SELLER_PAYABLE 은 created 시점에 즉시분
        // I=net−holdback 만 인식했다. gross 환불(전액/초과)이 net 전체를 삼키면 refundAmount 가 I 를
        // 넘어서므로, 조정 전 즉시지급 가능액을 여기서 먼저 고정해 뒤에서 상한으로 쓴다(수수료만큼
        // SELLER_PAYABLE·CASH 가 음수로 잔존하는 것을 방지). consumeHoldbackForRefund/adjustForRefund 가
        // 이 값을 변경하기 전에 캡처해야 한다.
        BigDecimal priorImmediate = settlement.getImmediatePayoutAmount();

        // ★ Holdback 우선 차감 정책: 보류금이 있으면 거기서 먼저 빼서 셀러 추가 부담 없게 한다.
        // 신뢰도 낮은 셀러의 환불 다발 위험을 정산 사이클 안에서 흡수하는 안전장치.
        BigDecimal consumedFromHoldback = settlement.consumeHoldbackForRefund(refundAmount);
        if (consumedFromHoldback.signum() > 0) {
            log.info("Holdback 에서 우선 차감: settlementId={}, consumed={}, holdbackRemaining={}",
                    settlement.getId(), consumedFromHoldback, settlement.getHoldbackAmount());
        }

        // 환불 반영 (도메인 로직) — 정산의 netAmount 재계산 (running total)
        settlement.adjustForRefund(refundAmount);
        Settlement adjustedSettlement = saveSettlementPort.save(settlement);

        // 감사 추적: 별도 음수 금액 레코드로 역정산 이력 보존 — refundId 로 환불과 1:1 매핑
        LocalDate today = LocalDate.now(clock);
        SettlementAdjustment adjustment = saveSettlementAdjustmentPort.save(SettlementAdjustment.ofRefund(
                adjustedSettlement.getId(),
                refundId,
                refundAmount,
                today
        ));

        log.info("Settlement adjusted for refund. settlementId={}, status={}, netAmount={}, adjustmentAmount={}",
                adjustedSettlement.getId(), adjustedSettlement.getStatus(),
                adjustedSettlement.getNetAmount(), adjustment.getAmount());

        // account 로 조정 이벤트 발행(ADR 0026 Option ①) — 셀러 미해석이면 조용히 생략(수기 대응).
        // Settlement 는 sellerId 를 보관하지 않으므로 발행 시점에 payment→seller 로 해석한다.
        loadSellerIdPort.findSellerIdByPaymentId(paymentId).ifPresent(sellerId -> {
            // ① 홀드백에서 흡수된 만큼은 유보 소진(현금유출)으로 전기된다.
            if (consumedFromHoldback.signum() > 0) {
                publishSettlementDomainEventPort.publishHoldbackConsumed(
                        adjustment.getId(), settlement.getId(), sellerId, consumedFromHoldback);
            }
            // ② 홀드백으로 흡수되지 못한 잔여 환불액은 즉시분(SELLER_PAYABLE) 조정으로 전기된다 — 단
            // priorImmediate(조정 전 즉시지급 가능액) 을 상한으로 캡핑한다(HIGH-A). gross 환불이 net 을
            // 넘어서도 SELLER_PAYABLE 은 인식된 즉시분 이상 차변되지 않는다 — 수수료(GL 밖 경계)는
            // happy-path 와 동일하게 미인식 상태로 남는다.
            BigDecimal payableDelta = refundAmount.subtract(consumedFromHoldback).min(priorImmediate);
            if (payableDelta.signum() > 0) {
                publishSettlementDomainEventPort.publishSettlementAdjusted(
                        adjustment.getId(), settlement.getId(), sellerId, payableDelta, "SELLER_PAYABLE");
            }
            // ③ 환불로 정산이 통째로 소멸(CANCELED)하면 ②에서 미처 정리되지 못한 잔여만 반제한다.
            // ②가 이미 priorImmediate 전액을 캡핑해 정리한 케이스에서는 즉시분 잔여가 0(또는 음수)이
            // 되므로 max(0) 가드로 클램프한다 — 음수 그대로 실으면 계약 스키마(minimum 0) 위반이자
            // 컨슈머 signum>0 스킵으로 조용히 무시되어 회계상 무해하지만, 계약 위반 자체를 막는다(HIGH-A).
            if (adjustedSettlement.getStatus() == SettlementStatus.CANCELED) {
                publishSettlementDomainEventPort.publishSettlementCanceled(
                        settlement.getId(), sellerId,
                        adjustedSettlement.getImmediatePayoutAmount().max(BigDecimal.ZERO),
                        adjustedSettlement.getHoldbackAmount().max(BigDecimal.ZERO));
            }
        });

        // refundId 가 있을 때만 ledger 역분개 트리거 — 레거시 2-arg 호출 경로 보호.
        // 같은 트랜잭션 아웃박스에 적재 → 커밋 후 로컬 폴러가 멱등 역분개 처리 (크래시 내성).
        if (refundId != null) {
            enqueueLedgerTaskPort.enqueueReverse(
                    adjustedSettlement.getId(), refundId, refundAmount, today);
        }

        return adjustedSettlement;
    }
}
