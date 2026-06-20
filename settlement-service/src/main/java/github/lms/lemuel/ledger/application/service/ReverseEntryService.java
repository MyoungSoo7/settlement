package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 환불 → 역분개 작성.
 *
 * <p>분개 모델 — 환불 1건 = 2 row (역방향):
 * <pre>
 * row1: Dr SALES_REFUND / Cr ACCOUNTS_PAYABLE     = refundedNet
 *       → 미지급금 환원 + 매출환불 인식
 * row2: Dr SALES_REFUND / Cr COMMISSION_REVENUE   = refundedCommission
 *       → 수수료수익 환원
 * </pre>
 *
 * <p>비율 분해: settlement 의 commission rate(=commission/payment)에 따라
 * refundAmount 를 (refundedCommission, refundedNet) 두 부분으로 쪼갠다.
 * 합계: refundedNet + refundedCommission = refundAmount.
 *
 * <p>멱등: 동일 refundId 로 이미 작성된 entry 가 있으면 skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseEntryService implements ReverseEntryUseCase {

    private final LoadSettlementForLedgerPort loadSettlementPort;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final SaveLedgerEntryPort saveLedgerPort;

    @Override
    @Transactional
    public List<LedgerEntry> reverseForRefund(Long settlementId, Long refundId,
                                              BigDecimal refundAmount, LocalDate adjustmentDate) {
        if (settlementId == null) {
            throw new IllegalArgumentException("settlementId 필수");
        }
        if (refundId == null) {
            throw new IllegalArgumentException("refundId 필수");
        }
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw new IllegalArgumentException("refundAmount 양수여야 합니다: " + refundAmount);
        }
        if (adjustmentDate == null) {
            throw new IllegalArgumentException("adjustmentDate 필수");
        }

        // 멱등.
        if (loadLedgerPort.existsByReference(refundId, ReferenceType.REFUND)) {
            log.debug("Reverse ledger already exists for refund {}; skipping", refundId);
            return Collections.emptyList();
        }

        SettlementSummary s = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Settlement not found for reverse entry: id=" + settlementId));

        BigDecimal payment = nullSafe(s.paymentAmount());
        BigDecimal commission = nullSafe(s.commission());

        if (payment.signum() <= 0) {
            throw new IllegalStateException(
                    "Settlement " + settlementId + " has non-positive paymentAmount");
        }
        if (refundAmount.compareTo(payment) > 0) {
            throw new IllegalArgumentException(
                    "refundAmount " + refundAmount + " exceeds paymentAmount " + payment);
        }

        // commission rate = commission / payment
        // refundedCommission = refundAmount × rate, scale=2 HALF_UP
        // refundedNet        = refundAmount − refundedCommission (잔여로 정의해 합계 정확히 일치)
        BigDecimal refundedCommission = refundAmount.multiply(commission)
                .divide(payment, 2, RoundingMode.HALF_UP);
        BigDecimal refundedNet = refundAmount.subtract(refundedCommission)
                .setScale(2, RoundingMode.HALF_UP);

        List<LedgerEntry> created = new ArrayList<>(2);

        if (refundedNet.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    refundId, ReferenceType.REFUND,
                    LedgerEntryType.REFUND_REVERSED,
                    AccountType.SALES_REFUND, AccountType.ACCOUNTS_PAYABLE,
                    refundedNet, adjustmentDate,
                    "환불 역분개 — 미지급금 환원 (settlement=" + settlementId + ")");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        if (refundedCommission.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    refundId, ReferenceType.REFUND,
                    LedgerEntryType.REFUND_REVERSED,
                    AccountType.SALES_REFUND, AccountType.COMMISSION_REVENUE,
                    refundedCommission, adjustmentDate,
                    "환불 역분개 — 수수료수익 환원 (settlement=" + settlementId + ")");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        log.info("Reverse ledger entries created: settlementId={}, refundId={}, refundAmount={}, rows={}",
                settlementId, refundId, refundAmount, created.size());
        return created;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
