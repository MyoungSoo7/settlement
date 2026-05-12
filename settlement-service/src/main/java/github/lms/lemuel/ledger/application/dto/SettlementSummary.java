package github.lms.lemuel.ledger.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ledger 도메인이 settlement 도메인을 직접 참조하지 않기 위한 read-only DTO.
 *
 * <p>분개 작성에 필요한 최소 필드만 포함한다.
 */
public record SettlementSummary(
        Long id,
        BigDecimal paymentAmount,
        BigDecimal commission,
        BigDecimal netAmount,
        LocalDate settlementDate,
        String status
) {
}
