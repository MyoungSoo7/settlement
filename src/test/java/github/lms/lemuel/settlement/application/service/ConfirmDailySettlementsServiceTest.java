package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase.ConfirmSettlementCommand;
import github.lms.lemuel.settlement.application.port.in.ConfirmDailySettlementsUseCase.ConfirmSettlementResult;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmDailySettlementsService")
class ConfirmDailySettlementsServiceTest {

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private PublishSettlementEventPort publishSettlementEventPort;

    @InjectMocks
    private ConfirmDailySettlementsService service;

    private final LocalDate TARGET_DATE = LocalDate.of(2026, 1, 15);

    private Settlement pendingSettlement(Long id) {
        Settlement s = new Settlement();
        s.setId(id);
        s.setStatus(SettlementStatus.PENDING);
        return s;
    }

    private Settlement nonPendingSettlement(Long id, SettlementStatus status) {
        Settlement s = new Settlement();
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    @Nested
    @DisplayName("정산 확정 성공")
    class Success {

        @Test
        @DisplayName("PENDING 정산 2건을 확정하고 이벤트를 발행한다")
        void confirm_twoPending_confirmsBoth() {
            List<Settlement> settlements = List.of(pendingSettlement(1L), pendingSettlement(2L));
            given(loadSettlementPort.findBySettlementDate(TARGET_DATE)).willReturn(settlements);
            given(saveSettlementPort.save(any())).willAnswer(inv -> inv.getArgument(0));

            ConfirmSettlementResult result = service.confirmDailySettlements(new ConfirmSettlementCommand(TARGET_DATE));

            assertThat(result.confirmedCount()).isEqualTo(2);
            assertThat(result.totalSettlements()).isEqualTo(2);
            then(saveSettlementPort).should(times(2)).save(any(Settlement.class));
            then(publishSettlementEventPort).should().publishSettlementConfirmedEvent(anyList());
        }

        @Test
        @DisplayName("PENDING이 아닌 정산은 건너뛴다")
        void confirm_skipsNonPendingSettlements() {
            List<Settlement> settlements = List.of(
                pendingSettlement(1L),
                nonPendingSettlement(2L, SettlementStatus.DONE),
                nonPendingSettlement(3L, SettlementStatus.CANCELED)
            );
            given(loadSettlementPort.findBySettlementDate(TARGET_DATE)).willReturn(settlements);
            given(saveSettlementPort.save(any())).willAnswer(inv -> inv.getArgument(0));

            ConfirmSettlementResult result = service.confirmDailySettlements(new ConfirmSettlementCommand(TARGET_DATE));

            assertThat(result.confirmedCount()).isEqualTo(1);
            assertThat(result.totalSettlements()).isEqualTo(3);
        }

        @Test
        @DisplayName("대상 정산이 없으면 이벤트를 발행하지 않는다")
        void confirm_noSettlements_noEventPublished() {
            given(loadSettlementPort.findBySettlementDate(TARGET_DATE)).willReturn(List.of());

            ConfirmSettlementResult result = service.confirmDailySettlements(new ConfirmSettlementCommand(TARGET_DATE));

            assertThat(result.confirmedCount()).isEqualTo(0);
            then(publishSettlementEventPort).should(never()).publishSettlementConfirmedEvent(anyList());
        }

        @Test
        @DisplayName("PENDING이 없으면 이벤트를 발행하지 않는다")
        void confirm_allNonPending_noEventPublished() {
            List<Settlement> settlements = List.of(
                nonPendingSettlement(1L, SettlementStatus.DONE)
            );
            given(loadSettlementPort.findBySettlementDate(TARGET_DATE)).willReturn(settlements);

            ConfirmSettlementResult result = service.confirmDailySettlements(new ConfirmSettlementCommand(TARGET_DATE));

            assertThat(result.confirmedCount()).isEqualTo(0);
            then(publishSettlementEventPort).should(never()).publishSettlementConfirmedEvent(anyList());
            then(saveSettlementPort).should(never()).save(any());
        }
    }
}