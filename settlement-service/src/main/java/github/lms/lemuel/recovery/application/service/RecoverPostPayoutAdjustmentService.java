package github.lms.lemuel.recovery.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.recovery.application.port.out.AbsorbSettlementHoldbackPort;
import github.lms.lemuel.recovery.application.port.out.LoadSellerRecoveryPort;
import github.lms.lemuel.recovery.application.port.out.PublishSellerRecoveryEventPort;
import github.lms.lemuel.recovery.application.port.out.SaveSellerRecoveryPort;
import github.lms.lemuel.recovery.domain.SellerRecovery;
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
 * 아니면 대상 아님 → ③ 미해제 holdback 에서 우선 흡수(정산 슬라이스에 위임, 셀러 해석 실패면
 * 아무것도 바꾸지 않고 종료 — 조정 레코드가 수기 대응 근거) → ④ 잔여만 채권으로 열고
 * 발생 분개(Dr AR / Cr AP) 1건.
 *
 * <p><b>정산 애그리거트를 직접 열지 않는다.</b> 홀드백 소진은 정산의 규칙이므로
 * {@link AbsorbSettlementHoldbackPort} 로 "얼마를 흡수해 달라"만 요청하고 결과만 받는다.
 * 이전에는 이 서비스가 settlement 의 출력 포트 4종을 주입받아 {@code Settlement} 를 로드·변경·저장하고
 * 정산 도메인 이벤트까지 발행했다.
 */
@Slf4j
@Service
public class RecoverPostPayoutAdjustmentService implements RecordPostPayoutRecoveryUseCase {

    private final LoadSellerRecoveryPort loadRecoveryPort;
    private final SaveSellerRecoveryPort saveRecoveryPort;
    private final LoadPayoutPort loadPayoutPort;
    private final AbsorbSettlementHoldbackPort absorbSettlementHoldbackPort;
    private final RecoveryEntryUseCase recoveryEntryUseCase;
    private final PublishSellerRecoveryEventPort publishSellerRecoveryEventPort;

    public RecoverPostPayoutAdjustmentService(LoadSellerRecoveryPort loadRecoveryPort,
                                              SaveSellerRecoveryPort saveRecoveryPort,
                                              LoadPayoutPort loadPayoutPort,
                                              AbsorbSettlementHoldbackPort absorbSettlementHoldbackPort,
                                              RecoveryEntryUseCase recoveryEntryUseCase,
                                              PublishSellerRecoveryEventPort publishSellerRecoveryEventPort) {
        this.loadRecoveryPort = loadRecoveryPort;
        this.saveRecoveryPort = saveRecoveryPort;
        this.loadPayoutPort = loadPayoutPort;
        this.absorbSettlementHoldbackPort = absorbSettlementHoldbackPort;
        this.recoveryEntryUseCase = recoveryEntryUseCase;
        this.publishSellerRecoveryEventPort = publishSellerRecoveryEventPort;
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

        Optional<AbsorbSettlementHoldbackPort.HoldbackAbsorption> absorption =
                absorbSettlementHoldbackPort.absorbForRecovery(settlementId, adjustmentId, recoveredAmount);
        if (absorption.isEmpty()) {
            // 정산 미발견 또는 셀러 미해석 — 사유 로그는 정산 슬라이스가 남긴다.
            return Optional.empty();
        }
        Long sellerId = absorption.get().sellerId();
        BigDecimal absorbed = absorption.get().absorbed();

        BigDecimal remainder = recoveredAmount.subtract(absorbed);
        if (remainder.signum() <= 0) {
            log.info("[Recovery] holdback 전액 흡수 — 채권 불필요. settlementId={}, absorbed={}",
                    settlementId, absorbed);
            return Optional.empty();
        }

        SellerRecovery recovery = saveRecoveryPort.save(
                SellerRecovery.open(adjustmentId, sellerId, remainder));
        recoveryEntryUseCase.recognizeReceivable(recovery.getId(), settlementId, remainder, adjustmentDate);
        // account 로 채권 발생(Opened) 이벤트 발행 — remainder 는 위 가드로 항상 양수.
        publishSellerRecoveryEventPort.publishRecoveryOpened(recovery.getId(), sellerId, remainder);
        log.warn("[Recovery] 지급후 회수 채권 발생. recoveryId={}, settlementId={}, adjustmentId={}, "
                        + "recovered={}, absorbed={}, receivable={}",
                recovery.getId(), settlementId, adjustmentId, recoveredAmount, absorbed, remainder);
        return Optional.of(recovery);
    }
}
