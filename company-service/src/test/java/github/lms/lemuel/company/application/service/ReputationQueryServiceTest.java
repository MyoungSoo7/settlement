package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationGrade;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReputationQueryServiceTest {

    @Mock
    private LoadCompanyPort loadCompanyPort;
    @Mock
    private LoadReputationPort loadReputationPort;
    @InjectMocks
    private ReputationQueryService service;

    private static final String STOCK = "005930";

    private ReputationScore snapshot() {
        return ReputationScore.rehydrate(STOCK, LocalDate.of(2026, 7, 7), 72, ReputationGrade.B,
                5, 1, 1, 3, Map.of(IssueCategory.LEGAL, 1), Instant.parse("2026-07-07T00:00:00Z"));
    }

    private void companyExists() {
        when(loadCompanyPort.findByStockCode(STOCK))
                .thenReturn(Optional.of(new Company(STOCK, null, "삼성전자", "KOSPI")));
    }

    @Test
    @DisplayName("current — 기업이 존재하면 최신 스냅샷을 반환한다")
    void currentReturnsLatest() {
        companyExists();
        when(loadReputationPort.findLatest(STOCK)).thenReturn(Optional.of(snapshot()));

        Optional<ReputationScore> result = service.current(STOCK);

        assertTrue(result.isPresent());
        assertEquals(72, result.get().score());
    }

    @Test
    @DisplayName("current — 스냅샷이 없어도 기업만 있으면 빈 Optional")
    void currentEmptyWhenNoSnapshot() {
        companyExists();
        when(loadReputationPort.findLatest(STOCK)).thenReturn(Optional.empty());

        assertTrue(service.current(STOCK).isEmpty());
    }

    @Test
    @DisplayName("존재하지 않는 기업이면 NoSuchElementException")
    void currentThrowsWhenCompanyMissing() {
        when(loadCompanyPort.findByStockCode(STOCK)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.current(STOCK));
    }

    @Test
    @DisplayName("history — limit 을 정상 범위로 전달한다")
    void historyPassesLimit() {
        companyExists();
        when(loadReputationPort.findHistory(eq(STOCK), eq(30))).thenReturn(List.of(snapshot()));

        List<ReputationScore> result = service.history(STOCK, 30);

        assertEquals(1, result.size());
        verify(loadReputationPort).findHistory(STOCK, 30);
    }

    @Test
    @DisplayName("history — limit 이 0 이하면 최소 1 로 보정한다")
    void historyClampsLowerBound() {
        companyExists();
        when(loadReputationPort.findHistory(eq(STOCK), eq(1))).thenReturn(List.of());

        service.history(STOCK, 0);

        verify(loadReputationPort).findHistory(STOCK, 1);
    }

    @Test
    @DisplayName("history — limit 이 상한(365)을 넘으면 365 로 보정한다")
    void historyClampsUpperBound() {
        companyExists();
        when(loadReputationPort.findHistory(eq(STOCK), eq(365))).thenReturn(List.of());

        service.history(STOCK, 10_000);

        verify(loadReputationPort).findHistory(STOCK, 365);
    }

    @Test
    @DisplayName("history — 존재하지 않는 기업이면 NoSuchElementException")
    void historyThrowsWhenCompanyMissing() {
        when(loadCompanyPort.findByStockCode(STOCK)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.history(STOCK, 10));
    }
}
