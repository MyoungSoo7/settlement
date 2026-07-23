package github.lms.lemuel.ledger.adapter.in.web.response;

import github.lms.lemuel.ledger.domain.LedgerPeriod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 원장 기간(월) 응답 — 마감 상태·스냅샷 합계·감사 필드(closedBy/closedAt).
 */
public record LedgerPeriodResponse(
        Long id,
        String periodYm,
        String status,
        LocalDateTime closedAt,
        String closedBy,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        LocalDateTime createdAt
) {
    public static LedgerPeriodResponse from(LedgerPeriod p) {
        return new LedgerPeriodResponse(
                p.getId(),
                p.getPeriodYm(),
                p.getStatus().name(),
                p.getClosedAt(),
                p.getClosedBy(),
                p.getTotalDebit(),
                p.getTotalCredit(),
                p.getCreatedAt());
    }
}
