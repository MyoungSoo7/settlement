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
    private final LedgerPeriodGuard periodGuard;

    @Override
    @Transactional
    public List<LedgerEntry> reverseForRefund(Long settlementId, Long refundId,
                                              BigDecimal refundAmount, LocalDate adjustmentDate) {
        return reverseForReference(settlementId, refundId, ReferenceType.REFUND,
                refundAmount, adjustmentDate);
    }

    @Override
    @Transactional
    public List<LedgerEntry> reverseForReference(Long settlementId, Long referenceId,
                                                 ReferenceType referenceType,
                                                 BigDecimal amount, LocalDate adjustmentDate) {
        if (settlementId == null) {
            throw new IllegalArgumentException("settlementId 필수");
        }
        if (referenceId == null) {
            throw new IllegalArgumentException("referenceId 필수");
        }
        if (referenceType == null || referenceType == ReferenceType.SETTLEMENT) {
            throw new IllegalArgumentException("역분개 referenceType 은 REFUND/CHARGEBACK/PG_RECONCILIATION: " + referenceType);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 양수여야 합니다: " + amount);
        }
        if (adjustmentDate == null) {
            throw new IllegalArgumentException("adjustmentDate 필수");
        }

        // 멱등 — 같은 (referenceId, referenceType) 로 이미 역분개가 있으면 skip.
        if (loadLedgerPort.existsByReference(referenceId, referenceType)) {
            log.debug("Reverse ledger already exists for {} {}; skipping", referenceType, referenceId);
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
        if (amount.compareTo(payment) > 0) {
            throw new IllegalArgumentException(
                    "amount " + amount + " exceeds paymentAmount " + payment);
        }

        // 기간 원장 잠금 — 마감 기간을 대상으로 한 역분개는 재개봉하지 않고 다음 OPEN 기간으로 재지정해 전기한다.
        LocalDate postingDate = periodGuard.resolveOpenPostingDate(adjustmentDate);

        LedgerEntryType entryType = entryTypeFor(referenceType);
        String label = labelFor(referenceType);

        // commission rate = commission / payment
        // reversedCommission = amount × rate, scale=2 HALF_UP
        // reversedNet        = amount − reversedCommission (잔여로 정의해 합계 정확히 일치)
        BigDecimal reversedCommission = amount.multiply(commission)
                .divide(payment, 2, RoundingMode.HALF_UP);
        BigDecimal reversedNet = amount.subtract(reversedCommission)
                .setScale(2, RoundingMode.HALF_UP);

        List<LedgerEntry> created = new ArrayList<>(2);

        if (reversedNet.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    referenceId, referenceType,
                    entryType,
                    AccountType.SALES_REFUND, AccountType.ACCOUNTS_PAYABLE,
                    reversedNet, postingDate,
                    label + " 역분개 — 미지급금 환원 (settlement=" + settlementId + ")");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        if (reversedCommission.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    referenceId, referenceType,
                    entryType,
                    AccountType.SALES_REFUND, AccountType.COMMISSION_REVENUE,
                    reversedCommission, postingDate,
                    label + " 역분개 — 수수료수익 환원 (settlement=" + settlementId + ")");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        log.info("Reverse ledger entries created: settlementId={}, {}={}, amount={}, rows={}",
                settlementId, referenceType, referenceId, amount, created.size());
        return created;
    }

    private static LedgerEntryType entryTypeFor(ReferenceType referenceType) {
        return switch (referenceType) {
            case REFUND -> LedgerEntryType.REFUND_REVERSED;
            case CHARGEBACK -> LedgerEntryType.CHARGEBACK_REVERSED;
            case PG_RECONCILIATION -> LedgerEntryType.RECON_REVERSED;
            default -> throw new IllegalArgumentException("역분개 불가 referenceType: " + referenceType);
        };
    }

    private static String labelFor(ReferenceType referenceType) {
        return switch (referenceType) {
            case REFUND -> "환불";
            case CHARGEBACK -> "차지백";
            case PG_RECONCILIATION -> "PG대사";
            default -> referenceType.name();
        };
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
