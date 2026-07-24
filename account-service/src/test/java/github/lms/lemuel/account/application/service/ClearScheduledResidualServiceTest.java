package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.ClearScheduledResidualUseCase.ClearingReport;
import github.lms.lemuel.account.application.port.out.AppendAccountEntryPort;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClearScheduledResidualServiceTest {

    @Mock LoadAccountEntryPort loadAccountEntryPort;
    @Mock AppendAccountEntryPort appendAccountEntryPort;
    @InjectMocks ClearScheduledResidualService service;

    private static AccountEntry legacyCreated(String sellerId, String settlementId, String amount) {
        return AccountEntry.reconstitute(null, OwnerType.SELLER, sellerId,
                GlAccount.SETTLEMENT_SCHEDULED, GlAccount.SELLER_PAYABLE, new BigDecimal(amount),
                "SETTLEMENT_CREATED", settlementId, "lemuel.settlement.created", LocalDateTime.now());
    }

    @Test
    void 잔존_예정금이_있으면_셀러별_청산분개를_적재하고_보고한다() {
        when(loadAccountEntryPort.findAll()).thenReturn(List.of(
                legacyCreated("777", "S1", "43425"),
                legacyCreated("888", "S2", "10000")));

        ClearingReport report = service.clearResidual();

        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(appendAccountEntryPort, org.mockito.Mockito.times(2)).append(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(e -> {
            assertThat(e.getDebitAccount()).isEqualTo(GlAccount.CASH);
            assertThat(e.getCreditAccount()).isEqualTo(GlAccount.SETTLEMENT_SCHEDULED);
            assertThat(e.getRefType()).isEqualTo("SETTLEMENT_SCHED_CLEARING");
        });
        assertThat(report.clearedSellers()).isEqualTo(2);
        assertThat(report.totalCleared()).isEqualByComparingTo("53425");
    }

    @Test
    void 잔존_예정금이_없으면_적재없이_0건_보고한다_멱등() {
        when(loadAccountEntryPort.findAll()).thenReturn(List.of(
                // 이미 폐루프로 닫힌 Option A 전표만 존재 → SETTLEMENT_SCHEDULED 순차변 없음
                AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("43425")),
                AccountEntry.payoutCompleted("777", "P1", new BigDecimal("43425"))));

        ClearingReport report = service.clearResidual();

        verify(appendAccountEntryPort, never()).append(org.mockito.ArgumentMatchers.any());
        assertThat(report.clearedSellers()).isZero();
        assertThat(report.totalCleared()).isEqualByComparingTo("0");
    }
}
