package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadCompanyWorkforcePort;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkforceHistory;
import github.lms.lemuel.company.domain.WorkplaceSeriesKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkforceHistoryQueryServiceTest {

    @Mock
    private LoadCompanyWorkforcePort loadCompanyWorkforcePort;

    private CompanyWorkforce snapshot(YearMonth month, int headcount, String billed) {
        return new CompanyWorkforce("주식회사에고이즘", "866759", "525101", "전자상거래 소매업",
                "서울특별시 성동구 연무장19길", month, headcount, new BigDecimal(billed));
    }

    @Test
    @DisplayName("시계열 키로 스냅샷을 전부 로드해 월 오름차순 히스토리를 만든다")
    void loadsSeriesAndBuildsHistory() {
        WorkplaceSeriesKey key = WorkplaceSeriesKey.of("주식회사에고이즘", "866759");
        when(loadCompanyWorkforcePort.findSeries(key)).thenReturn(List.of(
                snapshot(YearMonth.of(2026, 6), 60, "18000000"),
                snapshot(YearMonth.of(2026, 5), 50, "16406250")));

        WorkforceHistory history = new WorkforceHistoryQueryService(loadCompanyWorkforcePort).get(key);

        assertThat(history.points()).hasSize(2);
        assertThat(history.points().get(0).month()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(history.points().get(1).headcountChange()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("매칭 스냅샷이 0건이면 NoSuchElementException — 404 계약")
    void throwsNotFoundWhenSeriesEmpty() {
        WorkplaceSeriesKey key = WorkplaceSeriesKey.of("없는회사", "999999");
        when(loadCompanyWorkforcePort.findSeries(key)).thenReturn(List.of());

        assertThatThrownBy(() -> new WorkforceHistoryQueryService(loadCompanyWorkforcePort).get(key))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("사업장");
    }
}
