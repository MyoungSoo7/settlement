package github.lms.lemuel.ledger.adapter.in.web.response;

import github.lms.lemuel.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record LedgerEntryResponse(
        Long id,
        Long referenceId,
        String referenceType,
        String entryType,
        String debitAccount,
        String creditAccount,
        BigDecimal amount,
        String status,
        LocalDate settlementDate,
        LocalDateTime postedAt,
        String memo,
        LocalDateTime createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(),
                e.getReferenceId(),
                e.getReferenceType().name(),
                e.getEntryType().name(),
                e.getDebitAccount().name(),
                e.getCreditAccount().name(),
                e.getAmount(),
                e.getStatus().name(),
                e.getSettlementDate(),
                e.getPostedAt(),
                e.getMemo(),
                e.getCreatedAt()
        );
    }
}
