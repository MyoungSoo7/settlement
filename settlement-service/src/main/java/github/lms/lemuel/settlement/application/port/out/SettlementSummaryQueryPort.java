package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.SettlementSummaryDto;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 요약 조회 역할 — 일/월 단위 집계 화면 전용 (Read Model).
 *
 * <p>{@link QuerySettlementPort} 의 응집 축 중 하나(요약 대시보드). 요약만 쓰는 소비처는 이 역할만
 * 의존하면 된다(ISP).
 */
public interface SettlementSummaryQueryPort {

    List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate);

    List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate);
}
