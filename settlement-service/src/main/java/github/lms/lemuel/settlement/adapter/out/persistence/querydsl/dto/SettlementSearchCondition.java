package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class SettlementSearchCondition {
    private final String status;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Long userId;
    private final String ordererName;
    private final String productName;
    private final Boolean isRefunded;
    private final Long cursorId;
    private final LocalDate cursorDate;
    private final int size;
    private final String sortBy;
    private final String sortDirection;
}
