package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.in.ApplyReconciliationAdjustmentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import github.lms.lemuel.settlement.domain.exception.InvalidSettlementStateException;
import github.lms.lemuel.settlement.domain.exception.SettlementNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * PG 대사 승인 → 정산 역정산(clawback) 적용 서비스.
 *
 * <p>{@link AdjustSettlementForRefundService}(환불 역정산)를 미러링하되, 전용 도메인 메서드
 * {@link Settlement#applyReconciliationClawback}를 써서 {@code refundedAmount} running total 을
 * 오염시키지 않는다(실제 환불과의 이중 계상 방지). holdback 우선 차감 → net 축소 → 감사 레코드 순서.
 *
 * <p><b>NO ledger changes.</b> 원장 역분개는 후속 과제다 — 기존 {@code enqueueReverse}는 refundId 키
 * 기반이라 대사에 맞지 않는다. chargeback 경로와 동일하게 이 단계에서는 원장을 건드리지 않는다.
 */
@Service
@Transactional
public class ApplyReconciliationAdjustmentService implements ApplyReconciliationAdjustmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyReconciliationAdjustmentService.class);

    private static final String METRIC_APPLIED = "pg.reconciliation.adjustments.applied";
    private static final String METRIC_SKIPPED = "pg.reconciliation.adjustments.skipped";

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final SaveSettlementAdjustmentPort saveSettlementAdjustmentPort;
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    private final RecordPostPayoutRecoveryUseCase recordPostPayoutRecoveryUseCase;
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    private final MeterRegistry meterRegistry;
    private final AuditLogger auditLogger;
    /** KST 기준 시각 소스 — clawback 조정 기준일이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public ApplyReconciliationAdjustmentService(LoadSettlementPort loadSettlementPort,
                                                SaveSettlementPort saveSettlementPort,
                                                SaveSettlementAdjustmentPort saveSettlementAdjustmentPort,
                                                EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                                                RecordPostPayoutRecoveryUseCase recordPostPayoutRecoveryUseCase,
                                                LoadSellerIdPort loadSellerIdPort,
                                                PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                                MeterRegistry meterRegistry,
                                                AuditLogger auditLogger,
                                                Clock clock) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.saveSettlementAdjustmentPort = saveSettlementAdjustmentPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.recordPostPayoutRecoveryUseCase = recordPostPayoutRecoveryUseCase;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
        this.meterRegistry = meterRegistry;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public void applyClawback(Long paymentId, Long discrepancyId, BigDecimal clawbackAmount) {
        // 멱등 2단(belt-and-suspenders): processed_events 이후에도, 같은 discrepancy 로 조정 row 가
        // 이미 있으면 이중 회수 차단. 재전송·리플레이는 여기서 무해하게 흡수된다.
        if (saveSettlementAdjustmentPort.existsByReconciliationDiscrepancyId(discrepancyId)) {
            log.info("[PgRecon] 이미 적용된 대사 clawback 재전송 스킵. discrepancyId={}, paymentId={}",
                    discrepancyId, paymentId);
            meterRegistry.counter(METRIC_SKIPPED, "reason", "already_applied").increment();
            return;
        }

        // 동시성 제어: 환불 경로와 같은 비관적 락으로 정산 행을 잠근다(동시 조정 lost update 방지).
        Settlement settlement = loadSettlementPort.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(
                        "Settlement not found for paymentId: " + paymentId));

        // ADR 0026 Option ① 상한(HIGH-A, 환불 경로 AdjustSettlementForRefundService 와 동형) — consumeHoldback
        // ForRefund 는 DONE 에서도 holdback 을 차감해버리므로, 조정 전 즉시지급 가능액을 반드시 그 호출 이전에
        // 캡처해 둔다. gross clawback(전액/초과)이 net 전체를 삼켜도 SELLER_PAYABLE 은 이 값 이상 차변되지 않는다.
        BigDecimal priorImmediate = settlement.getImmediatePayoutAmount();

        LocalDate today = LocalDate.now(clock);
        BigDecimal consumedFromHoldback;
        try {
            // holdback 우선 차감(clawback 흡수) → net 축소. consumeHoldbackForRefund 는 사실상
            // "clawback 을 holdback 에서 먼저 흡수" 의미로 재사용한다.
            consumedFromHoldback = settlement.consumeHoldbackForRefund(clawbackAmount);
            settlement.applyReconciliationClawback(clawbackAmount); // DONE 이면 여기서 throw
        } catch (InvalidSettlementStateException done) {
            // DONE 정산: 도메인이 net 변경을 거부 → 이 인스턴스는 저장하지 않는다. 단 holdback 흡수는
            // 폐기로 끝나지 않는다 — recordIfPostPayout 이 fresh load 로 재적용·영속한다(미지급 유보금 감소).
            // 조정 ↔ 역분개 1:1(INV-5) 은 여기서도 지킨다 — 역분개가 깎는 미지급금(AP)의 반대급부는
            // 채권 발생 분개(Dr AR / Cr AP)가 미수금으로 전환한다. 송금 완료(payout COMPLETED)면
            // holdback 흡수 후 잔여를 채권으로 열어 후속 정산에서 상계한다(seed-p0-6).
            SettlementAdjustment adjustment = saveSettlementAdjustmentPort.save(
                    SettlementAdjustment.ofReconciliation(
                            settlement.getId(), discrepancyId, clawbackAmount, today));
            enqueueLedgerTaskPort.enqueueReverseReconciliation(
                    settlement.getId(), discrepancyId, clawbackAmount, today);
            recordPostPayoutRecoveryUseCase.recordIfPostPayout(
                    settlement.getId(), adjustment.getId(), clawbackAmount, today);
            log.warn("[PgRecon] DONE 정산 — clawback 직접 적용 불가, 조정·역분개·지급후 채권으로 처리. "
                            + "discrepancyId={}, settlementId={}, clawback={}",
                    discrepancyId, settlement.getId(), clawbackAmount);
            meterRegistry.counter(METRIC_SKIPPED, "reason", "settlement_done_manual_clawback").increment();
            recordAudit(settlement.getId(), discrepancyId, clawbackAmount, "DONE_MANUAL_CLAWBACK");
            return;
        }

        Settlement adjusted = saveSettlementPort.save(settlement);

        SettlementAdjustment adjustment = saveSettlementAdjustmentPort.save(SettlementAdjustment.ofReconciliation(
                adjusted.getId(), discrepancyId, clawbackAmount, today));

        // PG_RECONCILIATION 출처 원장 역분개를 같은 트랜잭션 Outbox 에 적재 — 조정 ↔ 역분개 1:1 (환불 경로 동형).
        // DONE 정산(위 catch) 은 지급 완료라 역분개하지 않는다(송금후 회수는 범위 밖). 이중 적재는
        // uq_ledger_reference_accounts (reference_type=PG_RECONCILIATION) 가 최종 차단한다.
        enqueueLedgerTaskPort.enqueueReverseReconciliation(adjusted.getId(), discrepancyId, clawbackAmount, today);

        // account 로 조정 이벤트 발행(ADR 0026 Option ①) — 환불 경로(AdjustSettlementForRefundService)와 동형.
        // DONE 정산(위 catch)의 회계 이벤트는 recordIfPostPayout(채권 발생·유보 소진)이 소유하므로 여기서는 발행하지 않는다.
        loadSellerIdPort.findSellerIdByPaymentId(paymentId).ifPresent(sellerId -> {
            if (consumedFromHoldback.signum() > 0) {
                publishSettlementDomainEventPort.publishHoldbackConsumed(
                        adjustment.getId(), adjusted.getId(), sellerId, consumedFromHoldback);
            }
            // priorImmediate 상한 캡핑(HIGH-A) — 환불 경로와 동형 이유.
            BigDecimal payableDelta = clawbackAmount.subtract(consumedFromHoldback).min(priorImmediate);
            if (payableDelta.signum() > 0) {
                publishSettlementDomainEventPort.publishSettlementAdjusted(
                        adjustment.getId(), adjusted.getId(), sellerId, payableDelta, "SELLER_PAYABLE");
            }
            // max(0) 가드(HIGH-A) — ②가 priorImmediate 전액을 이미 캡핑 정리한 경우 잔여가 음수가 될 수
            // 있어 계약(minimum 0) 위반을 막는다.
            if (adjusted.getStatus() == SettlementStatus.CANCELED) {
                publishSettlementDomainEventPort.publishSettlementCanceled(
                        adjusted.getId(), sellerId,
                        adjusted.getImmediatePayoutAmount().max(BigDecimal.ZERO),
                        adjusted.getHoldbackAmount().max(BigDecimal.ZERO));
            }
        });

        log.info("[PgRecon] 대사 clawback 적용 완료. discrepancyId={}, settlementId={}, clawback={}, netAmount={}, status={}",
                discrepancyId, adjusted.getId(), clawbackAmount, adjusted.getNetAmount(), adjusted.getStatus());
        meterRegistry.counter(METRIC_APPLIED).increment();
        recordAudit(adjusted.getId(), discrepancyId, clawbackAmount, "APPLIED");
    }

    /**
     * 대사 clawback 적용을 audit_logs 에 기록. 값은 전부 id/금액이라 주입 위험이 없어 컴팩트 JSON 을 직접 조립한다
     * (컨슈머 경로라 actor 는 system). AuditLogger 자체가 실패를 삼키므로 본 흐름은 영향받지 않는다.
     */
    private void recordAudit(Long settlementId, Long discrepancyId, java.math.BigDecimal clawbackAmount, String outcome) {
        String detail = String.format(
                "{\"settlementId\":%d,\"discrepancyId\":%d,\"clawbackAmount\":\"%s\",\"outcome\":\"%s\"}",
                settlementId, discrepancyId, clawbackAmount.toPlainString(), outcome);
        auditLogger.record(AuditAction.RECON_ADJUSTMENT_APPLIED, "Settlement",
                String.valueOf(settlementId), detail);
    }
}
