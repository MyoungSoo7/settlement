package github.lms.lemuel.financial.application.service;

import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.out.DartClientPort;
import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.application.port.out.SaveCompanyPort;
import github.lms.lemuel.financial.application.port.out.SaveFinancialStatementPort;
import github.lms.lemuel.financial.domain.Company;
import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.FsDivision;
import github.lms.lemuel.financial.domain.StatementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DartSyncServiceTest {

    @Mock
    private DartClientPort dartClient;
    @Mock
    private LoadCompanyPort loadCompanyPort;
    @Mock
    private SaveCompanyPort saveCompanyPort;
    @Mock
    private SaveFinancialStatementPort saveFinancialStatementPort;

    private DartSyncService service;

    @BeforeEach
    void setUp() {
        // 테스트에서는 호출 간격 0 (sleep 없음), 상한 없음
        service = new DartSyncService(dartClient, loadCompanyPort, saveCompanyPort,
                saveFinancialStatementPort, 0L, 0);
        lenient().when(dartClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("API 키 미설정이면 IllegalStateException — 배치 시작 자체를 거부")
    void requiresApiKey() {
        when(dartClient.isConfigured()).thenReturn(false);

        assertThatIllegalStateException().isThrownBy(() -> service.syncCompanies());
        assertThatIllegalStateException().isThrownBy(() -> service.syncStatements(2024));
    }

    @Test
    @DisplayName("기업 동기화 — 기업개황 corp_cls=Y(유가)만 upsert, 코스닥(K)은 스킵")
    void syncCompaniesFiltersKospi() {
        when(dartClient.fetchListedCompanies()).thenReturn(List.of(
                new DartClientPort.ListedCompany("00126380", "005930", "삼성전자"),
                new DartClientPort.ListedCompany("00256598", "247540", "에코프로비엠")));
        when(dartClient.fetchProfile("00126380"))
                .thenReturn(Optional.of(new DartClientPort.CompanyProfile("00126380", "Y", "삼성전자")));
        when(dartClient.fetchProfile("00256598"))
                .thenReturn(Optional.of(new DartClientPort.CompanyProfile("00256598", "K", "에코프로비엠")));
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.empty());

        SyncResult result = service.syncCompanies();

        assertThat(result).isEqualTo(new SyncResult(2, 1, 1, 0));
        ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
        verify(saveCompanyPort).upsert(captor.capture());
        assertThat(captor.getValue().stockCode()).isEqualTo("005930");
        assertThat(captor.getValue().corpCode()).isEqualTo("00126380");
    }

    @Test
    @DisplayName("기업 동기화 — 기존(시드) 행이 있으면 corp_code 를 병합해 upsert")
    void syncCompaniesMergesSeedRow() {
        when(dartClient.fetchListedCompanies()).thenReturn(List.of(
                new DartClientPort.ListedCompany("00126380", "005930", "삼성전자")));
        when(dartClient.fetchProfile("00126380"))
                .thenReturn(Optional.of(new DartClientPort.CompanyProfile("00126380", "Y", "삼성전자")));
        when(loadCompanyPort.findByStockCode("005930"))
                .thenReturn(Optional.of(new Company("005930", null, "삼성전자", "KOSPI")));

        service.syncCompanies();

        ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
        verify(saveCompanyPort).upsert(captor.capture());
        assertThat(captor.getValue().hasCorpCode()).isTrue();
    }

    @Test
    @DisplayName("기업 동기화 — 개별 기업 실패는 집계만 하고 배치는 계속 진행")
    void syncCompaniesCountsFailures() {
        when(dartClient.fetchListedCompanies()).thenReturn(List.of(
                new DartClientPort.ListedCompany("00000001", "111111", "실패기업"),
                new DartClientPort.ListedCompany("00126380", "005930", "삼성전자")));
        when(dartClient.fetchProfile("00000001")).thenThrow(new IllegalStateException("DART 오류"));
        when(dartClient.fetchProfile("00126380"))
                .thenReturn(Optional.of(new DartClientPort.CompanyProfile("00126380", "Y", "삼성전자")));
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.empty());

        SyncResult result = service.syncCompanies();

        assertThat(result).isEqualTo(new SyncResult(2, 1, 0, 1));
    }

    @Test
    @DisplayName("기업 동기화 — max-companies 상한만큼만 스캔")
    void syncCompaniesRespectsLimit() {
        service = new DartSyncService(dartClient, loadCompanyPort, saveCompanyPort,
                saveFinancialStatementPort, 0L, 1);
        when(dartClient.fetchListedCompanies()).thenReturn(List.of(
                new DartClientPort.ListedCompany("00126380", "005930", "삼성전자"),
                new DartClientPort.ListedCompany("00164742", "005380", "현대차")));
        when(dartClient.fetchProfile("00126380"))
                .thenReturn(Optional.of(new DartClientPort.CompanyProfile("00126380", "Y", "삼성전자")));
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.empty());

        SyncResult result = service.syncCompanies();

        assertThat(result.scanned()).isEqualTo(1);
        verify(dartClient, never()).fetchProfile("00164742");
    }

    @Test
    @DisplayName("재무제표 동기화 — 요약을 DART 출처 도메인으로 변환해 upsert, 미공시는 스킵")
    void syncStatements() {
        when(loadCompanyPort.findAllWithCorpCode()).thenReturn(List.of(
                new Company("005930", "00126380", "삼성전자", "KOSPI"),
                new Company("005380", "00164742", "현대차", "KOSPI")));
        when(dartClient.fetchAnnualSummary("00126380", 2024)).thenReturn(Optional.of(
                new DartClientPort.AnnualSummary(FsDivision.CFS, "KRW",
                        new BigDecimal("300900000000000"), new BigDecimal("32700000000000"),
                        new BigDecimal("34500000000000"), new BigDecimal("514500000000000"),
                        new BigDecimal("112300000000000"), new BigDecimal("402200000000000"))));
        when(dartClient.fetchAnnualSummary("00164742", 2024)).thenReturn(Optional.empty());

        SyncResult result = service.syncStatements(2024);

        assertThat(result).isEqualTo(new SyncResult(2, 1, 1, 0));
        ArgumentCaptor<FinancialStatement> captor = ArgumentCaptor.forClass(FinancialStatement.class);
        verify(saveFinancialStatementPort).upsert(captor.capture());
        FinancialStatement saved = captor.getValue();
        assertThat(saved.stockCode()).isEqualTo("005930");
        assertThat(saved.fiscalYear()).isEqualTo(2024);
        assertThat(saved.source()).isEqualTo(StatementSource.DART);
        assertThat(saved.isBalanced(new BigDecimal("0.5"))).isTrue();
    }

    @Test
    @DisplayName("재무제표 동기화 — 개별 실패는 집계, 저장은 호출되지 않음")
    void syncStatementsCountsFailures() {
        when(loadCompanyPort.findAllWithCorpCode()).thenReturn(List.of(
                new Company("005930", "00126380", "삼성전자", "KOSPI")));
        when(dartClient.fetchAnnualSummary(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("DART 오류"));

        SyncResult result = service.syncStatements(2024);

        assertThat(result).isEqualTo(new SyncResult(1, 0, 0, 1));
        verify(saveFinancialStatementPort, never()).upsert(any());
    }
}
