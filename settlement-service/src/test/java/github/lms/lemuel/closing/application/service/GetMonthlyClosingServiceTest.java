package github.lms.lemuel.closing.application.service;

import github.lms.lemuel.closing.application.dto.MonthlyClosingView;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyClosingPort;
import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;
import github.lms.lemuel.closing.domain.exception.ClosingRunNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMonthlyClosingServiceTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);

    @Mock LoadMonthlyClosingPort loadClosingPort;
    @InjectMocks GetMonthlyClosingService service;

    @Test
    void 마감_run_과_셀러_마트를_함께_반환한다() {
        MonthlyClosingRun run = MonthlyClosingRun.start(JULY, "admin", YearMonth.of(2026, 8));
        run.complete(1, 3, 0, 0, ClosingTotals.sumOf(List.of()));
        SellerMonthlyClosing row = SellerMonthlyClosing.of(JULY, 77L, 3,
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("3.50"),
                new BigDecimal("30.00"), new BigDecimal("96.50"));
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.of(run));
        when(loadClosingPort.findMart(JULY)).thenReturn(List.of(row));

        MonthlyClosingView view = service.getClosing(JULY);

        assertThat(view.run().getPeriodYm()).isEqualTo("2026-07");
        assertThat(view.sellers()).hasSize(1);
        assertThat(view.sellers().get(0).getSellerId()).isEqualTo(77L);
    }

    @Test
    void 마감_이력이_없는_월은_조회_불가() {
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClosing(JULY))
                .isInstanceOf(ClosingRunNotFoundException.class);
    }
}
