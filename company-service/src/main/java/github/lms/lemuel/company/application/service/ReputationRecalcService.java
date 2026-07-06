package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.application.port.out.SaveReputationPort;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.ReputationScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 저장된 기사에서 오늘자 평판 스냅샷을 산정한다(ADR 0023 Phase 2).
 *
 * <p>외부 호출이 없어(순수 DB 읽기 + 인메모리 분류) 동기 실행한다. INSERT-only 이므로 오늘자
 * 스냅샷이 이미 있으면 건너뛴다 — 재실행해도 그날 첫 스냅샷이 불변으로 남는다.
 */
@Service
public class ReputationRecalcService implements RecalcReputationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReputationRecalcService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LoadCompanyPort loadCompanyPort;
    private final LoadArticlePort loadArticlePort;
    private final AnalyzeSentimentPort analyzeSentimentPort;
    private final LoadReputationPort loadReputationPort;
    private final SaveReputationPort saveReputationPort;
    private final int windowDays;

    public ReputationRecalcService(LoadCompanyPort loadCompanyPort,
                                   LoadArticlePort loadArticlePort,
                                   AnalyzeSentimentPort analyzeSentimentPort,
                                   LoadReputationPort loadReputationPort,
                                   SaveReputationPort saveReputationPort,
                                   @Value("${app.company.reputation.window-days:30}") int windowDays) {
        this.loadCompanyPort = loadCompanyPort;
        this.loadArticlePort = loadArticlePort;
        this.analyzeSentimentPort = analyzeSentimentPort;
        this.loadReputationPort = loadReputationPort;
        this.saveReputationPort = saveReputationPort;
        this.windowDays = windowDays;
    }

    @Override
    public Optional<ReputationScore> recalcFor(String stockCode) {
        Company company = loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        Outcome outcome = recalcOne(company, Instant.now());
        return Optional.ofNullable(outcome.score());
    }

    @Override
    public RecalcSummary recalcAll() {
        Instant now = Instant.now();
        int saved = 0;
        int skippedNoArticle = 0;
        int skippedExisting = 0;
        List<Company> companies = loadCompanyPort.findAll();
        for (Company company : companies) {
            Outcome outcome = recalcOne(company, now);
            switch (outcome.status()) {
                case SAVED -> saved++;
                case NO_ARTICLE -> skippedNoArticle++;
                case EXISTS -> skippedExisting++;
            }
        }
        RecalcSummary summary = new RecalcSummary(companies.size(), saved, skippedNoArticle, skippedExisting);
        log.info("평판 전체 재계산 — {}", summary);
        return summary;
    }

    private Outcome recalcOne(Company company, Instant now) {
        LocalDate today = now.atZone(KST).toLocalDate();
        if (loadReputationPort.existsForDate(company.stockCode(), today)) {
            return Outcome.exists();
        }
        Instant since = now.minus(Duration.ofDays(windowDays));
        List<Article> articles = loadArticlePort.findForScoring(company.stockCode(), since);
        if (articles.isEmpty()) {
            return Outcome.noArticle();
        }
        List<ArticleSentiment> sentiments = articles.stream()
                .map(a -> analyzeSentimentPort.analyze(a.title(), a.summary()))
                .toList();
        ReputationScore score = ReputationScore.compute(company.stockCode(), today, sentiments, now);
        if (!saveReputationPort.saveIfAbsent(score)) {
            return Outcome.exists();   // 동시 재계산 레이스 — 다른 실행이 먼저 저장
        }
        log.info("평판 스냅샷 저장 stockCode={} score={} grade={} (기사 {}건)",
                company.stockCode(), score.score(), score.grade(), score.articleCount());
        return Outcome.saved(score);
    }

    private enum Status { SAVED, NO_ARTICLE, EXISTS }

    private record Outcome(Status status, ReputationScore score) {
        static Outcome saved(ReputationScore score) {
            return new Outcome(Status.SAVED, score);
        }

        static Outcome noArticle() {
            return new Outcome(Status.NO_ARTICLE, null);
        }

        static Outcome exists() {
            return new Outcome(Status.EXISTS, null);
        }
    }
}
