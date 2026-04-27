package github.lms.lemuel.settlement.adapter.in.web.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementPageResponse {
    private List<SettlementSearchItemResponse> settlements;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private int pageSize;
    private SettlementAggregationsResponse aggregations;
}