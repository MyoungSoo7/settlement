package github.lms.lemuel.card.application.service;

import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 평판 프로젝션 적재 서비스 — company 이벤트의 sellerIds 팬아웃이 셀러별 등급 upsert 로 매핑되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReputationProjectionServiceTest {

    @Mock SaveReputationPort saveReputationPort;
    @InjectMocks ReputationProjectionService service;

    @Test
    @DisplayName("sellerIds 전원에게 등급을 팬아웃 upsert 한다")
    void ingest_fansOutToEverySeller() {
        service.ingest(new ReputationCommand("C", List.of(777L, 1001L)));

        verify(saveReputationPort).upsertGrade("777", ReputationGrade.C);
        verify(saveReputationPort).upsertGrade("1001", ReputationGrade.C);
    }

    @Test
    @DisplayName("sellerIds 가 비어 있으면 아무것도 저장하지 않는다 — 기업에 링크된 셀러가 없는 정상 케이스")
    void ingest_emptySellersIsNoOp() {
        service.ingest(new ReputationCommand("A", List.of()));

        verifyNoInteractions(saveReputationPort);
    }

    @Test
    @DisplayName("알 수 없는 등급 문자열은 IllegalArgumentException — 즉시 DLT 격리 대상")
    void ingest_unknownGradeThrows() {
        assertThatThrownBy(() -> service.ingest(new ReputationCommand("Z", List.of(777L))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(saveReputationPort, never()).upsertGrade(any(), any());
    }
}
