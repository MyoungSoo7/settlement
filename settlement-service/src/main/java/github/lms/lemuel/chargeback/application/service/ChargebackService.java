package github.lms.lemuel.chargeback.application.service;

import github.lms.lemuel.chargeback.application.port.in.DecideChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.in.OpenChargebackUseCase;
import github.lms.lemuel.chargeback.application.port.out.LoadChargebackPort;
import github.lms.lemuel.chargeback.application.port.out.SaveChargebackPort;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
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
 *   <li>이미 Payout COMPLETED 된 정산의 환수(ReversePayout) 는 본 서비스 책임 아님 — Phase 3.</li>
 * </ul>
 */
@Service
@Transactional
public class ChargebackService implements OpenChargebackUseCase, DecideChargebackUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

    private final LoadChargebackPort loadPort;
    private final SaveChargebackPort savePort;
    private final SaveSettlementAdjustmentPort saveAdjustmentPort;

    private final Counter openedCounter;
    private final Counter acceptedCounter;
    private final Counter rejectedCounter;
    private final Counter idempotentHitCounter;

    public ChargebackService(LoadChargebackPort loadPort,
                              SaveChargebackPort savePort,
                              SaveSettlementAdjustmentPort saveAdjustmentPort,
                              MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.saveAdjustmentPort = saveAdjustmentPort;
        this.openedCounter = Counter.builder("chargeback.opened")
                .description("새로 등록된 분쟁 수").register(meterRegistry);
        this.acceptedCounter = Counter.builder("chargeback.accepted")
                .description("운영자가 ACCEPT 한 분쟁 — 셀러 환수 발생").register(meterRegistry);
        this.rejectedCounter = Counter.builder("chargeback.rejected")
                .description("운영자가 REJECT 한 분쟁 — 분쟁 종결, 정산 영향 없음").register(meterRegistry);
        this.idempotentHitCounter = Counter.builder("chargeback.idempotent.hit")
                .description("PG webhook 중복 통지로 멱등 hit 된 횟수").register(meterRegistry);
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
            SettlementAdjustment adjustment = SettlementAdjustment.ofChargeback(
                    saved.getSettlementId(),
                    saved.getId(),
                    saved.getAmount(),
                    LocalDate.now()
            );
            saveAdjustmentPort.save(adjustment);
            log.warn("[Chargeback] accepted + adjustment created. chargebackId={}, settlementId={}, amount={}",
                    saved.getId(), saved.getSettlementId(), saved.getAmount());
        } else {
            log.warn("[Chargeback] accepted (no settlement yet). chargebackId={}, paymentId={}",
                    saved.getId(), saved.getPaymentId());
        }

        acceptedCounter.increment();
        return saved;
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
