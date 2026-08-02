package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReputationProjectionServiceTest {

    @Mock SaveReputationPort saveReputationPort;

    ReputationProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ReputationProjectionService(saveReputationPort);
    }

    @Test
    void ingest_fansOutEachSellerId() {
        service.ingest(new ReputationCommand(List.of(777L, 1001L), "C"));

        verify(saveReputationPort).upsertGrade("777", "C");
        verify(saveReputationPort).upsertGrade("1001", "C");
    }

    @Test
    void ingest_emptySellerIds_noPortCalls() {
        service.ingest(new ReputationCommand(List.of(), "B"));

        verifyNoInteractions(saveReputationPort);
    }

    @Test
    void ingest_nullSellerIds_treatedAsEmpty() {
        service.ingest(new ReputationCommand(null, "A"));

        verify(saveReputationPort, never()).upsertGrade(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 등급을 검증하지 않으면 심사의 {@code gradeOf} 가 읽는 시점에 터진다 — haircut 계수를
     * 못 구해 카드 발급 전체가 500 이 된다. 적재 시점에 막아 DLT 로 보낸다.
     */
    @Test
    void ingest_unknownGrade_rejectedBeforeAnyFanOut() {
        assertThatThrownBy(() -> service.ingest(new ReputationCommand(List.of(777L, 1001L), "F")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F");

        verifyNoInteractions(saveReputationPort);
    }

    @Test
    void ingest_nullGrade_rejectedAtIngest() {
        assertThatThrownBy(() -> service.ingest(new ReputationCommand(List.of(777L), null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(saveReputationPort);
    }
}
