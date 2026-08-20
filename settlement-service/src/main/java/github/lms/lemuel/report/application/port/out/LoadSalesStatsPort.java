package github.lms.lemuel.report.application.port.out;

import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesSlice;

import java.util.List;

/**
 * 매출 통계 집계 조회 포트.
 *
 * <p>반환 타입이 전부 도메인 타입인 이유: application 은 adapter 를 참조할 수 없다
 * (ArchUnit 강제). 어댑터 전용 DTO 를 포트 시그니처에 올리면 그 순간 역의존이 생긴다 —
 * 이 저장소에서 실제로 있었던 위반이라 규칙이 강제로 잠겨 있다.
 */
public interface LoadSalesStatsPort {

    /** 기간 전체 합계. 거래가 없으면 0 으로 채운 합계를 준다(null 금지). */
    CashflowTotals totals(ReportPeriod period);

    /** 축별 구간 집계. 거래액 큰 순으로 상위 {@code limit} 개까지. */
    List<SalesSlice> slices(ReportPeriod period, SalesDimension dimension, int limit);
}
