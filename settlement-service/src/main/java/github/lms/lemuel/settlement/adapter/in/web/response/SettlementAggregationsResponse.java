package github.lms.lemuel.settlement.adapter.in.web.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementAggregationsResponse {
    private BigDecimal totalAmount;
    private BigDecimal totalRefundedAmount;
    private BigDecimal totalFinalAmount;
    private Map<String, Long> statusCounts;
}