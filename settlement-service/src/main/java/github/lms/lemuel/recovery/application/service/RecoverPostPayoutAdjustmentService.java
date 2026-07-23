package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 지급후 회수 채권 발생 (seed-p0-6) — 회수 조정 저장 트랜잭션에 합류해 원자적으로 적재한다.
 *
 * <p>판정·흡수·발생의 순서: ① 조정 1건=채권 1건 멱등 → ② 즉시지급 Payout COMPLETED(송금 완료)
 * 아니면 대상 아님 → ③ 셀러 해석 실패면 아무것도 바꾸지 않고 종료(조정 레코드가 수기 대응 근거)
 * → ④ 미해제 holdback 에서 우선 흡수 → ⑤ 잔여만 채권으로 열고 발생 분개(Dr AR / Cr AP) 1건.
 */
@Slf4j
@Service
public class RecoverPostPayoutAdjustmentService implements RecordPostPayoutRecoveryUseCase {

    private final LoadSellerRecoveryPort loadRecoveryPort;
    private final SaveSellerRecoveryPort saveRecoveryPort;
    private final LoadPayoutPort loadPayoutPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final RecoveryEntryUseCase recoveryEntryUseCase;

    public RecoverPostPayoutAdjustmentService(LoadSellerRecoveryPort loadRecoveryPort,
                                              SaveSellerRecoveryPort saveRecoveryPort,
                                              LoadPayoutPort loadPayoutPort,
                                              LoadSettlementPort loadSettlementPort,
                                              SaveSettlementPort saveSettlementPort,
                                              LoadSellerIdPort loadSellerIdPort,
                                              RecoveryEntryUseCase recoveryEntryUseCase) {
        this.loadRecoveryPort = loadRecoveryPort;
        this.saveRecoveryPort = saveRecoveryPort;
        this.loadPayoutPort = loadPayoutPort;
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.recoveryEntryUseCase = recoveryEntryUseCase;
    }

    @Override
    @Transactional
    public Optional<SellerRecovery> recordIfPostPayout(Long settlementId, Long adjustmentId,
                                                       BigDecimal recoveredAmount, LocalDate adjustmentDate) {
        if (loadRecoveryPort.findBySourceAdjustmentId(adjustmentId).isPresent()) {
            return Optional.empty();
        }
        boolean paidOut = loadPayoutPort.findBySettlementIdAndType(settlementId, PayoutType.IMMEDIATE)
                .map(payout -> payout.getStatus() == PayoutStatus.COMPLETED)
                .orElse(false);
        if (!paidOut) {
            return Optional.empty();
        }
        Optional<Settlement> loaded = loadSettlementPort.findById(settlementId);
        if (loaded.isEmpty()) {
            log.warn("[Recovery] 정산 미발견 — 채권 발생 생략. settlementId={}, adjustmentId={}",
                    settlementId, adjustmentId);
            return Optional.empty();
        }
        Settlement settlement = loaded.get();

        Optional<Long> sellerId = loadSellerIdPort.findSellerIdByPaymentId(settlement.getPaymentId());
        if (sellerId.isEmpty()) {
            log.warn("[Recovery] 셀러 미해석 — 채권 발생 생략(조정 레코드로 수기 대응). "
                    + "settlementId={}, adjustmentId={}", settlementId, adjustmentId);
            return Optional.empty();
        }

        BigDecimal absorbed = settlement.consumeHoldbackForRefund(recoveredAmount);
        if (absorbed.signum() > 0) {
            saveSettlementPort.save(settlement);
        }
        BigDecimal remainder = recoveredAmount.subtract(absorbed);
        if (remainder.signum() <= 0) {
            log.info("[Recovery] holdback 전액 흡수 — 채권 불필요. settlementId={}, absorbed={}",
                    settlementId, absorbed);
            return Optional.empty();
        }

        SellerRecovery recovery = saveRecoveryPort.save(
                SellerRecovery.open(adjustmentId, sellerId.get(), remainder));
        recoveryEntryUseCase.recognizeReceivable(recovery.getId(), settlementId, remainder, adjustmentDate);
        log.warn("[Recovery] 지급후 회수 채권 발생. recoveryId={}, settlementId={}, adjustmentId={}, "
                        + "recovered={}, absorbed={}, receivable={}",
                recovery.getId(), settlementId, adjustmentId, recoveredAmount, absorbed, remainder);
        return Optional.of(recovery);
    }
}
