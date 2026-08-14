package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAccessDeniedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryInstallmentSavingsServiceTest {

    @Mock
    private LoadInstallmentSavingsPort loadInstallmentSavingsPort;

    @InjectMocks
    private QueryInstallmentSavingsService service;

    private static InstallmentSavings savings(Long id, String depositorId) {
        return InstallmentSavings.reconstitute(id, depositorId, "정액적립 3개월", SavingsType.FIXED,
                new BigDecimal("100000"), null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                SavingsStatus.ACTIVE, null, null, null, List.of());
    }

    @Test
    void 본인_계약은_단건_조회된다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings(9L, "42")));

        InstallmentSavings result = service.get(9L, "42");

        assertThat(result.getId()).isEqualTo(9L);
    }

    @Test
    void 없는_계약을_조회하면_찾을_수_없다는_예외가_난다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L, "42"))
                .isInstanceOf(SavingsNotFoundException.class);
    }

    @Test
    void 남의_계약은_조회할_수_없다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings(9L, "42")));

        assertThatThrownBy(() -> service.get(9L, "999"))
                .isInstanceOf(SavingsAccessDeniedException.class);
    }

    @Test
    void 목록은_예금주로_조회해_남의_행이_섞일_여지가_없다() {
        when(loadInstallmentSavingsPort.findByDepositorId("42"))
                .thenReturn(List.of(savings(9L, "42"), savings(10L, "42")));

        List<InstallmentSavings> result = service.listMine("42");

        assertThat(result).extracting(InstallmentSavings::getId).containsExactly(9L, 10L);
        assertThat(result).allMatch(s -> "42".equals(s.getDepositorId()));
    }
}
