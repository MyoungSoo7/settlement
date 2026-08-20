package github.lms.lemuel.report.application.service;

import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase;
import github.lms.lemuel.report.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesBreakdown;
import github.lms.lemuel.report.domain.SalesComparison;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 매출 통계 조회 서비스.
 *
 * <p>서비스가 지는 책임은 두 가지다.
 *
 * <ol>
 *   <li><b>직전 기간을 스스로 잡는다.</b> 화면이 분모를 넘기게 두면 화면마다 "전기"의 정의가
 *       달라져 같은 데이터에서 다른 증감률이 나온다. {@link ReportPeriod#previous()} 하나로 고정한다.
 *   <li><b>상위 N 을 클램프한다.</b> 랭킹 축(셀러·상품)은 값의 가짓수가 계정 수만큼 늘어나므로,
 *       limit 이 열려 있으면 대시보드 한 번이 전수 스캔 + 수만 행 직렬화가 된다.
 * </ol>
 *
 * <p>집계 자체와 구성비 계산은 각각 어댑터와 도메인의 몫이다 — 여기서는 조립만 한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SalesStatsService implements QuerySalesStatsUseCase {

    /** 랭킹 상한. 화면이 한 번에 읽을 수 있는 행 수를 넘어서면 응답만 무거워진다. */
    static final int MAX_LIMIT = 100;
    static final int MIN_LIMIT = 1;

    private final LoadSalesStatsPort loadSalesStatsPort;

    @Override
    public SalesSummary summary(ReportPeriod period) {
        ReportPeriod previousPeriod = period.previous();
        // 호출 순서가 곧 비교의 방향이다 — 현재가 분자, 직전이 분모.
        CashflowTotals current = loadSalesStatsPort.totals(period);
        CashflowTotals previous = loadSalesStatsPort.totals(previousPeriod);
        return new SalesSummary(period, previousPeriod, new SalesComparison(current, previous));
    }

    @Override
    public SalesBreakdown breakdown(ReportPeriod period, SalesDimension dimension, int limit) {
        List<SalesSlice> slices = loadSalesStatsPort.slices(period, dimension, clamp(limit));
        return SalesBreakdown.from(slices);
    }

    /** 0·음수는 1 로 올린다 — LIMIT 0 은 오류 없이 빈 화면을 만들어 "데이터가 없다"로 오독된다. */
    private static int clamp(int limit) {
        return Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
    }
}
