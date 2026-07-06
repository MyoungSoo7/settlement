package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.IngestCompanyReputationUseCase.IngestCompanyReputationCommand;
import github.lms.lemuel.loan.application.port.out.LoadCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveCompanyReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveSellerReputationPort;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyReputationServiceTest {

    private final SaveCompanyReputationPort savePort = mock(SaveCompanyReputationPort.class);
    private final LoadCompanyReputationPort loadPort = mock(LoadCompanyReputationPort.class);
    private final SaveSellerReputationPort saveSellerPort = mock(SaveSellerReputationPort.class);
    private final CompanyReputationService service =
            new CompanyReputationService(savePort, loadPort, saveSellerPort);

    @Test
    @DisplayName("ingest 는 기업 프로젝션 UPSERT + 동봉 셀러별 프로젝션 UPSERT")
    void ingestUpserts() {
        service.ingest(new IngestCompanyReputationCommand(
                "005930", 50, "C", "B", LocalDate.of(2026, 7, 7), List.of(7L, 9L)));

        ArgumentCaptor<CompanyReputation> captor = ArgumentCaptor.forClass(CompanyReputation.class);
        verify(savePort).upsert(captor.capture());
        CompanyReputation saved = captor.getValue();
        assertEquals("005930", saved.getStockCode());
        assertEquals(50, saved.getScore());
        assertEquals("C", saved.getGrade());
        assertEquals("B", saved.getPreviousGrade());
        assertEquals(LocalDate.of(2026, 7, 7), saved.getSnapshotDate());
        // 링크된 셀러 2건 각각 셀러 프로젝션 적재
        verify(saveSellerPort).upsert(7L, "005930", 50, "C");
        verify(saveSellerPort).upsert(9L, "005930", 50, "C");
    }

    @Test
    @DisplayName("링크된 셀러가 없으면 셀러 프로젝션은 적재하지 않는다")
    void ingestWithoutSellers() {
        service.ingest(new IngestCompanyReputationCommand(
                "005930", 50, "C", "B", LocalDate.of(2026, 7, 7), List.of()));

        verify(savePort).upsert(org.mockito.ArgumentMatchers.any());
        verify(saveSellerPort, never()).upsert(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("byStockCode 는 로드 포트에 위임한다")
    void queryDelegates() {
        CompanyReputation rep = new CompanyReputation("005930", 50, "C", "B", LocalDate.of(2026, 7, 7));
        when(loadPort.findByStockCode("005930")).thenReturn(Optional.of(rep));

        Optional<CompanyReputation> result = service.byStockCode("005930");

        assertTrue(result.isPresent());
        assertEquals("C", result.get().getGrade());
    }
}
