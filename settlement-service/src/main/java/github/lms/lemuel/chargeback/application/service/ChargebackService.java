package github.lms.lemuel.chargeback.application.service;

import github.lms.lemuel.chargeback.application.port.in.BackfillChargebackSettlementUseCase;
import github.lms.lemuel.chargeback.application.port.in.DecideChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.application.port.out.SaveChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementAdjustmentPort;
import github.lms.lemuel.settlement.domain.SettlementAdjustment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Chargeback 핵심 서비스.
 *
 * <p>책임:
 * <ul>
 *   <li>새 분쟁 등록 — PG_WEBHOOK 은 멱등 (pgChargebackId 중복 시 기존 record 반환)</li>
 *   <li>운영자 결정 (accept/reject)</li>
 *   <li>ACCEPT 시 settlement_adjustments 음수 row 생성 (settlementId 가 있을 때만)</li>
 * </ul>
 *
 * <p>도메인 경계:
 * <ul>
 *   <li>{@link SaveSettlementAdjustmentPort} 는 같은 서비스(settlement-service) 내 다른 컨텍스트 포트.
 *       Chargeback 이 Settlement 의 도메인 모델을 직접 import 하지 않고 audit row 만 남긴다.</li>
 *   <li>정산 전 분쟁은 정산 생성 시 {@link BackfillChargebackSettlementUseCase} 백필로 연결 —
 *       ACCEPTED 사전분쟁의 환수 조정이 이 경로에서 만들어진다.</li>
 *   <li>이미 Payout COMPLETED 된 정산의 환수는 {@link RecordPostPayoutRecoveryUseCase} 로 위임 —
 *       holdback 흡수 후 잔여를 채권으로 열어 후속 정산에서 상계한다(seed-p0-6).</li>
 * </ul>
 */
@Service
@Transactional
public class ChargebackService
        implements OpenChargebackUseCase, DecideChargebackUseCase, BackfillChargebackSettlementUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

    private final LoadChargebackPort loadPort;
    private final SaveChargebackPort savePort;
    private final SaveSettlementAdjustmentPort saveAdjustmentPort;
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    private final RecordPostPayoutRecoveryUseCase recordPostPayoutRecoveryUseCase;

    private final Counter openedCounter;
    private final Counter acceptedCounter;
    private final Counter rejectedCounter;
    private final Counter idempotentHitCounter;
    private final Counter backfilledCounter;

    public ChargebackService(LoadChargebackPort loadPort,
                              SaveChargebackPort savePort,
                              SaveSettlementAdjustmentPort saveAdjustmentPort,
                              EnqueueLedgerTaskPort enqueueLedgerTaskPort,
                              RecordPostPayoutRecoveryUseCase recordPostPayoutRecoveryUseCase,
                              MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.saveAdjustmentPort = saveAdjustmentPort;
        this.enqueueLedgerTaskPort = enqueueLedgerTaskPort;
        this.recordPostPayoutRecoveryUseCase = recordPostPayoutRecoveryUseCase;
        this.openedCounter = Counter.builder("chargeback.opened")
                .description("새로 등록된 분쟁 수").register(meterRegistry);
        this.acceptedCounter = Counter.builder("chargeback.accepted")
                .description("운영자가 ACCEPT 한 분쟁 — 셀러 환수 발생").register(meterRegistry);
        this.rejectedCounter = Counter.builder("chargeback.rejected")
                .description("운영자가 REJECT 한 분쟁 — 분쟁 종결, 정산 영향 없음").register(meterRegistry);
        this.idempotentHitCounter = Counter.builder("chargeback.idempotent.hit")
                .description("PG webhook 중복 통지로 멱등 hit 된 횟수").register(meterRegistry);
        this.backfilledCounter = Counter.builder("chargeback.settlement.backfilled")
                .description("정산 생성 시 백필 연결된 사전분쟁 수").register(meterRegistry);
    }

    @Override
    public Chargeback open(OpenChargebackCommand command) {
        // PG_WEBHOOK 멱등 — 같은 pgChargebackId 통지가 두 번 와도 한 번만 OPEN
        if (command.source() == ChargebackSource.PG_WEBHOOK && command.pgChargebackId() != null) {
            var existing = loadPort.findByPgChargebackId(command.pgChargebackId());
            if (existing.isPresent()) {
                idempotentHitCounter.increment();
                log.info("[Chargeback] idempotent hit. pgChargebackId={}, existingId={}",
                        command.pgChargebackId(), existing.get().getId());
                return existing.get();
            }
        }

        Chargeback chargeback = Chargeback.open(
                command.paymentId(),
                command.settlementId(),
                command.amount(),
                command.reasonCode(),
                command.reasonDetail(),
                command.source(),
                command.pgChargebackId()
        );
        Chargeback saved = savePort.save(chargeback);
        openedCounter.increment();
        log.warn("[Chargeback] opened. id={}, paymentId={}, amount={}, reason={}, source={}",
                saved.getId(), saved.getPaymentId(), saved.getAmount(),
                saved.getReasonCode(), saved.getSource());
        return saved;
    }

    @Override
    public Chargeback accept(Long chargebackId, String decidedBy, String note) {
        Chargeback chargeback = loadPort.findById(chargebackId)
                .orElseThrow(() -> new IllegalArgumentException("Chargeback not found: " + chargebackId));

        chargeback.accept(decidedBy, note);
        Chargeback saved = savePort.save(chargeback);

        // 정산이 이미 생성된 분쟁만 즉시 adjustment 기록.
        // 정산 전 분쟁은 정산 생성 시 chargeback 조회 후 백필 (Phase 3).
        if (saved.getSettlementId() != null) {
            recordAdjustmentAndReverse(saved.getSettlementId(), saved.getId(), saved.getAmount());
            log.warn("[Chargeback] accepted + adjustment created. chargebackId={}, settlementId={}, amount={}",
                    saved.getId(), saved.getSettlementId(), saved.getAmount());
        } else {
            log.warn("[Chargeback] accepted (no settlement yet). chargebackId={}, paymentId={}",
                    saved.getId(), saved.getPaymentId());
        }

        acceptedCounter.increment();
        return saved;
    }

    /**
     * 차지백 환수 조정(음수 감사 레코드)과 CHARGEBACK 출처 원장 역분개 작업을 같은 트랜잭션에 함께 남긴다.
     * 조정만 남기고 원장을 안 건드리면 (조정 ↔ 역분개) 1:1 이 깨져 INV-5 가 갭으로 잡는다 —
     * 환불 경로({@code AdjustSettlementForRefundService})와 동일하게 원자적으로 적재한다.
     * 이중 적재는 uq_ledger_reference_accounts (reference_id, reference_type=CHARGEBACK) 가 최종 차단한다.
     */
    private void recordAdjustmentAndReverse(Long settlementId, Long chargebackId, java.math.BigDecimal amount) {
        LocalDate today = LocalDate.now();
        SettlementAdjustment adjustment = saveAdjustmentPort.save(
                SettlementAdjustment.ofChargeback(settlementId, chargebackId, amount, today));
        enqueueLedgerTaskPort.enqueueReverseChargeback(settlementId, chargebackId, amount, today);
        // 송금 완료(payout COMPLETED) 정산의 회수는 net 재계산이 불가능하다 — holdback 흡수 후 잔여를
        // 채권으로 열어 후속 정산에서 상계한다(seed-p0-6). 대상 아님 판정은 유스케이스가 스스로 한다.
        recordPostPayoutRecoveryUseCase.recordIfPostPayout(settlementId, adjustment.getId(), amount, today);
    }

    /**
     * 정산 생성 시 사전분쟁 백필 — 같은 결제의 정산 미연결 분쟁을 연결하고,
     * 이미 ACCEPTED 인 건은 지금 환수 조정을 만든다(accept 시점에 settlementId 가 없어 못 만든 것).
     * 중복 조정은 {@code uq_adjustments_chargeback_id}(분쟁 1건=조정 1건) 가 DB 에서 최종 차단한다.
     */
    @Override
    public int backfillSettlementLink(Long paymentId, Long settlementId) {
        var unlinked = loadPort.findUnlinkedByPaymentId(paymentId);
        if (unlinked.isEmpty()) {
            return 0;
        }
        for (Chargeback chargeback : unlinked) {
            chargeback.linkSettlement(settlementId);
            Chargeback saved = savePort.save(chargeback);
            backfilledCounter.increment();
            if (saved.isAccepted()) {
                recordAdjustmentAndReverse(settlementId, saved.getId(), saved.getAmount());
                log.warn("[Chargeback] 사전분쟁 백필 + 환수 조정 생성. chargebackId={}, settlementId={}, amount={}",
                        saved.getId(), settlementId, saved.getAmount());
            } else {
                log.info("[Chargeback] 사전분쟁 백필 연결. chargebackId={}, settlementId={}, status={}",
                        saved.getId(), settlementId, saved.getStatus());
            }
        }
        return unlinked.size();
    }

    @Override
    public Chargeback reject(Long chargebackId, String decidedBy, String note) {
        Chargeback chargeback = loadPort.findById(chargebackId)
                .orElseThrow(() -> new IllegalArgumentException("Chargeback not found: " + chargebackId));

        chargeback.reject(decidedBy, note);
        Chargeback saved = savePort.save(chargeback);
        rejectedCounter.increment();
        log.warn("[Chargeback] rejected. id={}, decidedBy={}, note={}",
                saved.getId(), decidedBy, note);
        return saved;
    }
}
