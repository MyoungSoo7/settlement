package github.lms.lemuel.settlement.adapter.in.web.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementSearchItemResponse {
    private Long settlementId;
    private Long orderId;
    private Long paymentId;
    private String ordererName;
    private String productName;
    private BigDecimal amount;
    private BigDecimal refundedAmount;
    private BigDecimal finalAmount;
    private String status;
    @JsonProperty("isRefunded")
    private boolean isRefunded;
    private LocalDate settlementDate;
}