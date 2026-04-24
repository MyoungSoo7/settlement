package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.JournalEntry;
import github.lms.lemuel.ledger.domain.Money;

public interface RecordJournalEntryUseCase {
    JournalEntry recordJournalEntry(JournalEntry entry);
    void recordSettlementCreated(Long settlementId, Long sellerId, Money paymentAmount, Money commissionAmount);
    void recordRefundProcessed(Long refundId, Long sellerId, Money refundAmount, Money commissionReversal);
}
