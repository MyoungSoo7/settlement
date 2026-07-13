package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementConfirmItemReaderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 22);

    @Mock LoadSettlementPort loadSettlementPort;

    private Settlement settlement(long id) {
        Settlement s = Settlement.createFromPayment(id, id + 10, new BigDecimal("10000"), DATE);
        s.assignId(id);
        return s;
    }

    private SettlementConfirmItemReader reader(int pageSize) {
        return new SettlementConfirmItemReader(loadSettlementPort, DATE.toString(), pageSize);
    }

    @Test
    @DisplayName("가득 찬 페이지면 다음 페이지를 재조회하고, 빈 페이지에서 종료한다")
    void paginatesUntilEmpty() {
        when(loadSettlementPort.findConfirmableForUpdate(eq(DATE), eq(2)))
                .thenReturn(List.of(settlement(1L), settlement(2L)), List.of());
        SettlementConfirmItemReader reader = reader(2);

        assertThat(reader.read()).extracting(Settlement::getId).isEqualTo(1L);
        assertThat(reader.read()).extracting(Settlement::getId).isEqualTo(2L);
        assertThat(reader.read()).isNull();

        verify(loadSettlementPort, times(2)).findConfirmableForUpdate(eq(DATE), eq(2));
    }

    @Test
    @DisplayName("부분 페이지(pageSize 미만)면 더 조회하지 않고 소진 후 종료")
    void stopsAfterPartialPage() {
        when(loadSettlementPort.findConfirmableForUpdate(eq(DATE), eq(2)))
                .thenReturn(List.of(settlement(1L)));
        SettlementConfirmItemReader reader = reader(2);

        assertThat(reader.read()).extracting(Settlement::getId).isEqualTo(1L);
        assertThat(reader.read()).isNull();

        verify(loadSettlementPort, times(1)).findConfirmableForUpdate(eq(DATE), eq(2));
    }

    @Test
    @DisplayName("첫 조회가 비면 즉시 종료(null)")
    void emptyImmediately() {
        when(loadSettlementPort.findConfirmableForUpdate(eq(DATE), eq(2))).thenReturn(List.of());
        SettlementConfirmItemReader reader = reader(2);

        assertThat(reader.read()).isNull();
        verify(loadSettlementPort, times(1)).findConfirmableForUpdate(eq(DATE), eq(2));
    }

    @Test
    @DisplayName("targetDate 파라미터가 비면 전일로 폴백(NPE 방지)")
    void blankDateFallsBackToYesterday() {
        SettlementConfirmItemReader reader =
                new SettlementConfirmItemReader(loadSettlementPort, " ", 2);
        when(loadSettlementPort.findConfirmableForUpdate(eq(LocalDate.now().minusDays(1)), eq(2)))
                .thenReturn(List.of());

        assertThat(reader.read()).isNull();
        verify(loadSettlementPort).findConfirmableForUpdate(eq(LocalDate.now().minusDays(1)), eq(2));
    }
}
