package github.lms.lemuel.financial.adapter.out.persistence;

import github.lms.lemuel.financial.application.port.out.LoadCompanyPort.SearchResult;
import github.lms.lemuel.financial.domain.Company;
import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.FsDivision;
import github.lms.lemuel.financial.domain.StatementSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영속성 어댑터 2종(Company·FinancialStatement)의 매핑·upsert(신규/기존 병합)·검색 분기와,
 * 그에 딸린 JPA 엔티티 fromDomain/applyDomain/toDomain 왕복 매핑을 Mockito 리포지토리로 검증.
 */
@ExtendWith(MockitoExtension.class)
class FinancialPersistenceAdapterTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FinancialStatementRepository statementRepository;

    private Company sampleCompany() {
        return new Company("005930", "00126380", "삼성전자", "KOSPI");
    }

    private FinancialStatement sampleStatement() {
        return new FinancialStatement(null, "005930", 2024, FsDivision.CFS, "KRW",
                new BigDecimal("1000"), new BigDecimal("150"), new BigDecimal("100"),
                new BigDecimal("2000"), new BigDecimal("800"), new BigDecimal("1200"),
                StatementSource.DART, Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ---- CompanyPersistenceAdapter ----

    @Test
    @DisplayName("search — keyword null 이면 findAll 정렬 페이지")
    void searchAll() {
        CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(companyRepository);
        Page<CompanyJpaEntity> page = new PageImpl<>(
                List.of(CompanyJpaEntity.fromDomain(sampleCompany())),
                PageRequest.of(0, 20), 1);
        when(companyRepository.findAll(any(Pageable.class))).thenReturn(page);

        SearchResult result = adapter.search(null, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).stockCode()).isEqualTo("005930");
        assertThat(result.content().get(0).name()).isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("search — keyword 있으면 검색 쿼리 사용")
    void searchByKeyword() {
        CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(companyRepository);
        Page<CompanyJpaEntity> page = new PageImpl<>(
                List.of(CompanyJpaEntity.fromDomain(sampleCompany())));
        when(companyRepository.search(eq("삼성"), any(Pageable.class))).thenReturn(page);

        SearchResult result = adapter.search("삼성", 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        verify(companyRepository).search(eq("삼성"), any(Pageable.class));
    }

    @Test
    @DisplayName("findByStockCode / findAllWithCorpCode 매핑")
    void companyLookups() {
        CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(companyRepository);
        when(companyRepository.findById("005930"))
                .thenReturn(Optional.of(CompanyJpaEntity.fromDomain(sampleCompany())));
        when(companyRepository.findByCorpCodeIsNotNull())
                .thenReturn(List.of(CompanyJpaEntity.fromDomain(sampleCompany())));

        assertThat(adapter.findByStockCode("005930")).map(Company::corpCode).contains("00126380");
        assertThat(adapter.findAllWithCorpCode()).hasSize(1);
    }

    @Test
    @DisplayName("upsert — 신규는 fromDomain 저장")
    void companyUpsertNew() {
        CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(companyRepository);
        when(companyRepository.findById("005930")).thenReturn(Optional.empty());

        adapter.upsert(sampleCompany());

        ArgumentCaptor<CompanyJpaEntity> captor = ArgumentCaptor.forClass(CompanyJpaEntity.class);
        verify(companyRepository).save(captor.capture());
        assertThat(captor.getValue().toDomain().stockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("upsert — 기존 행이면 applyDomain 병합 저장")
    void companyUpsertExisting() {
        CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(companyRepository);
        CompanyJpaEntity existing = CompanyJpaEntity.fromDomain(
                new Company("005930", null, "구삼성", "KOSPI"));
        when(companyRepository.findById("005930")).thenReturn(Optional.of(existing));

        adapter.upsert(sampleCompany());

        ArgumentCaptor<CompanyJpaEntity> captor = ArgumentCaptor.forClass(CompanyJpaEntity.class);
        verify(companyRepository).save(captor.capture());
        Company merged = captor.getValue().toDomain();
        assertThat(merged.corpCode()).isEqualTo("00126380");
        assertThat(merged.name()).isEqualTo("삼성전자");
    }

    // ---- FinancialStatementPersistenceAdapter ----

    @Test
    @DisplayName("findByCompany — 엔티티→도메인 매핑")
    void statementFindByCompany() {
        FinancialStatementPersistenceAdapter adapter =
                new FinancialStatementPersistenceAdapter(statementRepository);
        when(statementRepository.findByCompany("005930", null, null))
                .thenReturn(List.of(FinancialStatementJpaEntity.fromDomain(sampleStatement())));

        List<FinancialStatement> found = adapter.findByCompany("005930", null, null);

        assertThat(found).hasSize(1);
        FinancialStatement fs = found.get(0);
        assertThat(fs.stockCode()).isEqualTo("005930");
        assertThat(fs.fiscalYear()).isEqualTo(2024);
        assertThat(fs.fsDivision()).isEqualTo(FsDivision.CFS);
        assertThat(fs.revenue()).isEqualByComparingTo("1000");
        assertThat(fs.totalEquity()).isEqualByComparingTo("1200");
        assertThat(fs.source()).isEqualTo(StatementSource.DART);
    }

    @Test
    @DisplayName("upsert — 신규는 fromDomain 저장")
    void statementUpsertNew() {
        FinancialStatementPersistenceAdapter adapter =
                new FinancialStatementPersistenceAdapter(statementRepository);
        when(statementRepository.findByStockCodeAndFiscalYearAndFsDiv("005930", 2024, FsDivision.CFS))
                .thenReturn(Optional.empty());

        adapter.upsert(sampleStatement());

        verify(statementRepository).save(any(FinancialStatementJpaEntity.class));
    }

    @Test
    @DisplayName("upsert — 기존 행이면 applyDomain 병합 저장")
    void statementUpsertExisting() {
        FinancialStatementPersistenceAdapter adapter =
                new FinancialStatementPersistenceAdapter(statementRepository);
        FinancialStatementJpaEntity existing = FinancialStatementJpaEntity.fromDomain(
                new FinancialStatement(9L, "005930", 2024, FsDivision.CFS, "KRW",
                        new BigDecimal("1"), null, null, null, null, null,
                        StatementSource.SEED, Instant.parse("2025-01-01T00:00:00Z")));
        when(statementRepository.findByStockCodeAndFiscalYearAndFsDiv("005930", 2024, FsDivision.CFS))
                .thenReturn(Optional.of(existing));

        adapter.upsert(sampleStatement());

        ArgumentCaptor<FinancialStatementJpaEntity> captor =
                ArgumentCaptor.forClass(FinancialStatementJpaEntity.class);
        verify(statementRepository).save(captor.capture());
        FinancialStatement merged = captor.getValue().toDomain();
        assertThat(merged.revenue()).isEqualByComparingTo("1000");
        assertThat(merged.source()).isEqualTo(StatementSource.DART);
    }
}
