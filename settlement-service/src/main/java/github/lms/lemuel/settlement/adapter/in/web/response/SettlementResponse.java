package github.lms.lemuel.settlement.adapter.in.web.response;

import github.lms.lemuel.settlement.domain.Settlement;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementResponse {

    private Long id;
    private Long paymentId;
    private Long orderId;
    private BigDecimal paymentAmount;
    private BigDecimal commission;
    private BigDecimal netAmount;
    private String status;
    private LocalDate settlementDate;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SettlementResponse from(Settlement settlement) {
        return new SettlementResponse(
                settlement.getId(),
                settlement.getPaymentId(),
                settlement.getOrderId(),
                settlement.getPaymentAmount(),
                settlement.getCommission(),
                settlement.getNetAmount(),
                settlement.getStatus().name(),
                settlement.getSettlementDate(),
                settlement.getConfirmedAt(),
                settlement.getCreatedAt(),
                settlement.getUpdatedAt()
        );
    }
}
