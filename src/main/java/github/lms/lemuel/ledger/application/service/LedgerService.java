package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.RecordJournalEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadAccountPort;
import github.lms.lemuel.ledger.application.port.out.SaveJournalEntryPort;
import github.lms.lemuel.ledger.domain.*;
import github.lms.lemuel.ledger.domain.exception.DuplicateJournalEntryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LedgerService implements RecordJournalEntryUseCase {

    private final SaveJournalEntryPort saveJournalEntryPort;
    private final LoadAccountPort loadAccountPort;

    @Override
    public JournalEntry recordJournalEntry(JournalEntry entry) {
        if (saveJournalEntryPort.existsByIdempotencyKey(entry.getIdempotencyKey())) {
            throw new DuplicateJournalEntryException(entry.getIdempotencyKey());
        }
        JournalEntry saved = saveJournalEntryPort.save(entry);
        log.info("분개 기록 완료: type={}, ref={}:{}, idempotencyKey={}",
                saved.getEntryType(), saved.getReferenceType(), saved.getReferenceId(),
                saved.getIdempotencyKey());
        return saved;
    }

    @Override
    public void recordSettlementCreated(Long settlementId, Long sellerId,
                                         Money paymentAmount, Money commissionAmount) {
        Account platformCash = loadAccountPort.getOrCreate(Account.createPlatformCash());
        Account sellerPayable = loadAccountPort.getOrCreate(Account.createSellerPayable(sellerId));
        Account platformCommission = loadAccountPort.getOrCreate(Account.createPlatformCommission());

        // 1. 정산 생성 분개: 현금 유입 + 판매자 지급 의무
        recordJournalEntry(JournalEntry.create(
                "SETTLEMENT_CREATED", "SETTLEMENT", settlementId,
                List.of(
                        LedgerLine.debit(platformCash, paymentAmount),
                        LedgerLine.credit(sellerPayable, paymentAmount)
                ),
                "SETTLEMENT_CREATED:" + settlementId,
                "결제 캡처 → 정산 생성"
        ));

        // 2. 수수료 차감 분개: 판매자 지급액에서 수수료 차감
        recordJournalEntry(JournalEntry.create(
                "COMMISSION_DEDUCTED", "SETTLEMENT", settlementId,
                List.of(
                        LedgerLine.debit(sellerPayable, commissionAmount),
                        LedgerLine.credit(platformCommission, commissionAmount)
                ),
                "COMMISSION_DEDUCTED:" + settlementId,
                "수수료 차감"
        ));

        log.info("정산 Ledger 분개 완료: settlementId={}, payment={}, commission={}",
                settlementId, paymentAmount, commissionAmount);
    }

    @Override
    public void recordRefundProcessed(Long refundId, Long sellerId,
                                       Money refundAmount, Money commissionReversal) {
        Account platformCash = loadAccountPort.getOrCreate(Account.createPlatformCash());
        Account sellerPayable = loadAccountPort.getOrCreate(Account.createSellerPayable(sellerId));
        Account platformCommission = loadAccountPort.getOrCreate(Account.createPlatformCommission());

        Money sellerDeduction = refundAmount.subtract(commissionReversal);

        recordJournalEntry(JournalEntry.create(
                "REFUND_PROCESSED", "REFUND", refundId,
                List.of(
                        LedgerLine.debit(sellerPayable, sellerDeduction),
                        LedgerLine.debit(platformCommission, commissionReversal),
                        LedgerLine.credit(platformCash, refundAmount)
                ),
                "REFUND_PROCESSED:" + refundId,
                "환불 처리 (수수료 비례 역산 포함)"
        ));

        log.info("환불 Ledger 분개 완료: refundId={}, refundAmount={}, commissionReversal={}",
                refundId, refundAmount, commissionReversal);
    }
}
