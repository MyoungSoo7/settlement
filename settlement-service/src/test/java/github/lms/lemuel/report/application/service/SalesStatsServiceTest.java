package github.lms.lemuel.report.application.service;

import github.lms.lemuel.report.application.port.in.QuerySalesStatsUseCase.SalesSummary;
import github.lms.lemuel.report.application.port.out.LoadSalesStatsPort;
import github.lms.lemuel.report.domain.CashflowTotals;
import github.lms.lemuel.report.domain.ReportPeriod;
import github.lms.lemuel.report.domain.SalesBreakdown;
import github.lms.lemuel.report.domain.SalesDimension;
import github.lms.lemuel.report.domain.SalesSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매출 통계 조회 서비스 — 기간 두 개(현재·직전)를 모아 비교하고, 축별 구성비를 만든다.
 *
 * <p>서비스가 지는 책임은 두 가지뿐이다: <b>직전 기간을 스스로 잡는 것</b>(화면이 정하게 두면
 * 화면마다 분모가 달라진다)과 <b>상위 N 을 클램프하는 것</b>(limit 이 열려 있으면 랭킹 조회가
 * 전 셀러 스캔이 된다).
 */
@ExtendWith(MockitoExtension.class)
class SalesStatsServiceTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

    @Mock
    LoadSalesStatsPort loadSalesStatsPort;

    SalesStatsService service;

    @BeforeEach
    void setUp() {
        service = new SalesStatsService(loadSalesStatsPort);
    }

    private static CashflowTotals totals(long count, String gmv) {
        return new CashflowTotals(count, new BigDecimal(gmv), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(gmv), BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("요약")
    class Summary {

        @Test
        @DisplayName("현재 기간과 직전 동일 길이 기간을 각각 집계한다")
        void loadsBothPeriods() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.totals(any())).thenReturn(totals(10, "1000"));

            service.summary(period);

            ArgumentCaptor<ReportPeriod> captor = ArgumentCaptor.forClass(ReportPeriod.class);
            verify(loadSalesStatsPort, org.mockito.Mockito.times(2)).totals(captor.capture());
            assertThat(captor.getAllValues()).containsExactly(period, period.previous());
        }

        @Test
        @DisplayName("직전 기간은 서비스가 잡는다 — 화면이 분모를 정하지 않는다")
        void previousPeriodIsDerived() {
            when(loadSalesStatsPort.totals(any())).thenReturn(totals(1, "100"));

            SalesSummary summary = service.summary(ReportPeriod.of(JAN_1, JAN_31));

            assertThat(summary.previousPeriod().from()).isEqualTo(LocalDate.of(2025, 12, 1));
            assertThat(summary.previousPeriod().to()).isEqualTo(LocalDate.of(2025, 12, 31));
        }

        @Test
        @DisplayName("증감 비교를 함께 돌려준다")
        void carriesComparison() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.totals(period)).thenReturn(totals(20, "2000"));
            when(loadSalesStatsPort.totals(period.previous())).thenReturn(totals(10, "1000"));

            SalesSummary summary = service.summary(period);

            assertThat(summary.comparison().gmvGrowthRate()).isEqualByComparingTo("1.0000");
            assertThat(summary.period()).isEqualTo(period);
        }
    }

    @Nested
    @DisplayName("구성비")
    class Breakdown {

        @Test
        @DisplayName("포트가 준 구간에 정렬·구성비를 입혀 돌려준다")
        void wrapsSlices() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.slices(eq(period), eq(SalesDimension.PAYMENT_METHOD), anyInt()))
                    .thenReturn(List.of(
                            new SalesSlice("TRANSFER", 1, new BigDecimal("2500"),
                                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2500")),
                            new SalesSlice("CARD", 3, new BigDecimal("7500"),
                                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("7500"))));

            SalesBreakdown breakdown = service.breakdown(period, SalesDimension.PAYMENT_METHOD, 10);

            assertThat(breakdown.shares()).extracting(share -> share.label())
                    .containsExactly("CARD", "TRANSFER");
            assertThat(breakdown.totalGmv()).isEqualByComparingTo("10000");
        }
    }

    @Nested
    @DisplayName("상위 N 클램프")
    class LimitClamp {

        @Test
        @DisplayName("0 이하는 1 로 올린다 — LIMIT 0 은 빈 화면을 낳는다")
        void clampsLow() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.slices(any(), any(), anyInt())).thenReturn(List.of());

            service.breakdown(period, SalesDimension.SELLER, 0);

            verify(loadSalesStatsPort).slices(period, SalesDimension.SELLER, 1);
        }

        @Test
        @DisplayName("상한을 넘으면 100 으로 자른다 — 랭킹 조회가 전수 스캔이 되어선 안 된다")
        void clampsHigh() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.slices(any(), any(), anyInt())).thenReturn(List.of());

            service.breakdown(period, SalesDimension.PRODUCT, 5000);

            verify(loadSalesStatsPort).slices(period, SalesDimension.PRODUCT, 100);
        }

        @Test
        @DisplayName("범위 안의 값은 그대로 넘긴다")
        void passesThrough() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_31);
            when(loadSalesStatsPort.slices(any(), any(), anyInt())).thenReturn(List.of());

            service.breakdown(period, SalesDimension.SELLER_TIER, 20);

            verify(loadSalesStatsPort).slices(period, SalesDimension.SELLER_TIER, 20);
        }
    }
}
