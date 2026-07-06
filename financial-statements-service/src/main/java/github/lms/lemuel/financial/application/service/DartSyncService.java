package github.lms.lemuel.financial.application.service;

import github.lms.lemuel.financial.application.port.in.SyncCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.in.SyncStatementsUseCase;
import github.lms.lemuel.financial.application.port.out.DartClientPort;
import github.lms.lemuel.financial.application.port.out.LoadCompanyPort;
import github.lms.lemuel.financial.application.port.out.SaveCompanyPort;
import github.lms.lemuel.financial.application.port.out.SaveFinancialStatementPort;
import github.lms.lemuel.financial.domain.Company;
import github.lms.lemuel.financial.domain.FinancialStatement;
import github.lms.lemuel.financial.domain.StatementSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * DART 수집 배치.
 *
 * <p>기업 동기화: corpCode.xml 상장사 → 기업개황으로 유가(Y)만 필터 → 종목코드 기준 upsert.
 * 재무제표 동기화: corp_code 보유 기업별 사업보고서 주요계정 → (기업, 연도, 구분) upsert.
 *
 * <p>호출 간 간격(requestIntervalMs)으로 DART 쿼터(일 2만 콜)를 보호하고, 개별 기업 실패는
 * 집계만 하고 계속 진행한다(전체 배치가 한 기업 때문에 죽지 않게).
 */
@Service
public class DartSyncService implements SyncCompaniesUseCase, SyncStatementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DartSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 100;

    private final DartClientPort dartClient;
    private final LoadCompanyPort loadCompanyPort;
    private final SaveCompanyPort saveCompanyPort;
    private final SaveFinancialStatementPort saveFinancialStatementPort;
    private final long requestIntervalMs;
    private final int maxCompanies;

    public DartSyncService(DartClientPort dartClient,
                           LoadCompanyPort loadCompanyPort,
                           SaveCompanyPort saveCompanyPort,
                           SaveFinancialStatementPort saveFinancialStatementPort,
                           @Value("${app.financial.sync.request-interval-ms:150}") long requestIntervalMs,
                           @Value("${app.financial.sync.max-companies:0}") int maxCompanies) {
        this.dartClient = dartClient;
        this.loadCompanyPort = loadCompanyPort;
        this.saveCompanyPort = saveCompanyPort;
        this.saveFinancialStatementPort = saveFinancialStatementPort;
        this.requestIntervalMs = requestIntervalMs;
        this.maxCompanies = maxCompanies;
    }

    @Override
    public SyncResult syncCompanies() {
        requireConfigured();
        List<DartClientPort.ListedCompany> listed = dartClient.fetchListedCompanies();
        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int failed = 0;
        for (DartClientPort.ListedCompany candidate : listed) {
            if (maxCompanies > 0 && scanned >= maxCompanies) {
                break;
            }
            scanned++;
            try {
                Optional<DartClientPort.CompanyProfile> profile = dartClient.fetchProfile(candidate.corpCode());
                if (profile.isEmpty() || !profile.get().isKospi()) {
                    skipped++;
                } else {
                    Company merged = loadCompanyPort.findByStockCode(candidate.stockCode())
                            .map(existing -> existing.mergedWith(candidate.corpCode(), candidate.name()))
                            .orElseGet(() -> new Company(candidate.stockCode(), candidate.corpCode(),
                                    candidate.name(), "KOSPI"));
                    saveCompanyPort.upsert(merged);
                    upserted++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("기업 동기화 실패 corpCode={} name={}: {}", candidate.corpCode(), candidate.name(), e.getMessage());
            }
            logProgress("기업", scanned, listed.size(), upserted, failed);
            pause();
        }
        log.info("기업 동기화 완료 — 스캔 {}, 코스피 upsert {}, 스킵 {}, 실패 {}", scanned, upserted, skipped, failed);
        return new SyncResult(scanned, upserted, skipped, failed);
    }

    @Override
    public SyncResult syncStatements(int year) {
        requireConfigured();
        List<Company> companies = loadCompanyPort.findAllWithCorpCode();
        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int failed = 0;
        for (Company company : companies) {
            scanned++;
            try {
                Optional<DartClientPort.AnnualSummary> summary =
                        dartClient.fetchAnnualSummary(company.corpCode(), year);
                if (summary.isEmpty()) {
                    skipped++;   // 미공시(신규 상장 등)
                } else {
                    saveFinancialStatementPort.upsert(toStatement(company, year, summary.get()));
                    upserted++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("재무제표 동기화 실패 stockCode={} year={}: {}", company.stockCode(), year, e.getMessage());
            }
            logProgress("재무제표", scanned, companies.size(), upserted, failed);
            pause();
        }
        log.info("재무제표 동기화 완료 — 연도 {}, 대상 {}, upsert {}, 미공시 {}, 실패 {}",
                year, scanned, upserted, skipped, failed);
        return new SyncResult(scanned, upserted, skipped, failed);
    }

    private FinancialStatement toStatement(Company company, int year, DartClientPort.AnnualSummary s) {
        return new FinancialStatement(null, company.stockCode(), year, s.fsDivision(), s.currency(),
                s.revenue(), s.operatingProfit(), s.netIncome(),
                s.totalAssets(), s.totalLiabilities(), s.totalEquity(),
                StatementSource.DART, null);
    }

    private void requireConfigured() {
        if (!dartClient.isConfigured()) {
            throw new IllegalStateException("DART API 키가 설정되지 않았습니다 (DART_API_KEY)");
        }
    }

    private void logProgress(String job, int scanned, int total, int upserted, int failed) {
        if (scanned % PROGRESS_LOG_INTERVAL == 0) {
            log.info("{} 동기화 진행 {}/{} (upsert {}, 실패 {})", job, scanned, total, upserted, failed);
        }
    }

    private void pause() {
        if (requestIntervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동기화 스레드 인터럽트", e);
        }
    }
}
