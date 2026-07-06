package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.RecalcReputationUseCase;
import github.lms.lemuel.company.application.port.out.AnalyzeSentimentPort;
import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.LoadReputationPort;
import github.lms.lemuel.company.application.port.out.SaveReputationPort;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReputationRecalcServiceTest {

    private final LoadCompanyPort loadCompanyPort = mock(LoadCompanyPort.class);
    private final LoadArticlePort loadArticlePort = mock(LoadArticlePort.class);
    private final AnalyzeSentimentPort analyzeSentimentPort = mock(AnalyzeSentimentPort.class);
    private final LoadReputationPort loadReputationPort = mock(LoadReputationPort.class);
    private final SaveReputationPort saveReputationPort = mock(SaveReputationPort.class);
    private final ReputationRecalcService service = new ReputationRecalcService(
            loadCompanyPort, loadArticlePort, analyzeSentimentPort, loadReputationPort, saveReputationPort, 30);

    private static final Company SAMSUNG = new Company("005930", null, "삼성전자", null);

    private static Article article() {
        return Article.collect("005930", ArticleSource.NAVER_NEWS, "제목", "요약", "a.com",
                "https://a.com/1", Instant.now());
    }

    @Test
    @DisplayName("존재하지 않는 기업은 거부")
    void rejectsUnknownCompany() {
        when(loadCompanyPort.findByStockCode("999999")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.recalcFor("999999"));
    }

    @Test
    @DisplayName("오늘자 스냅샷이 이미 있으면 분류·저장하지 않고 건너뛴다 (INSERT-only)")
    void skipsWhenTodaysSnapshotExists() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));
        when(loadReputationPort.existsForDate(eq("005930"), any(LocalDate.class))).thenReturn(true);

        Optional<ReputationScore> result = service.recalcFor("005930");

        assertTrue(result.isEmpty());
        verify(loadArticlePort, never()).findForScoring(anyString(), any());
        verify(saveReputationPort, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("기사가 없으면 스냅샷을 만들지 않는다")
    void skipsWhenNoArticles() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));
        when(loadReputationPort.existsForDate(eq("005930"), any(LocalDate.class))).thenReturn(false);
        when(loadArticlePort.findForScoring(eq("005930"), any())).thenReturn(List.of());

        Optional<ReputationScore> result = service.recalcFor("005930");

        assertTrue(result.isEmpty());
        verify(saveReputationPort, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("기사를 분류해 스냅샷을 산정·저장한다")
    void computesAndSavesSnapshot() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));
        when(loadReputationPort.existsForDate(eq("005930"), any(LocalDate.class))).thenReturn(false);
        when(loadArticlePort.findForScoring(eq("005930"), any())).thenReturn(List.of(article(), article()));
        when(analyzeSentimentPort.analyze(any(), any()))
                .thenReturn(ArticleSentiment.negative(IssueCategory.LEGAL), ArticleSentiment.positive());
        when(saveReputationPort.saveIfAbsent(any())).thenReturn(true);

        Optional<ReputationScore> result = service.recalcFor("005930");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().articleCount());
        assertEquals(1, result.get().negativeCount());
        // 가중합 3, 분모 2*3=6 → 100-50 = 50
        assertEquals(50, result.get().score());
        verify(saveReputationPort).saveIfAbsent(any());
    }

    @Test
    @DisplayName("전체 재계산은 저장·기사없음·기존존재를 각각 집계한다")
    void recalcAllCategorizesOutcomes() {
        Company naver = new Company("035420", null, "NAVER", null);
        Company kakao = new Company("035720", null, "카카오", null);
        when(loadCompanyPort.findAll()).thenReturn(List.of(SAMSUNG, naver, kakao));
        // 삼성: 저장 / NAVER: 기사없음 / 카카오: 오늘 스냅샷 존재
        when(loadReputationPort.existsForDate(eq("005930"), any(LocalDate.class))).thenReturn(false);
        when(loadReputationPort.existsForDate(eq("035420"), any(LocalDate.class))).thenReturn(false);
        when(loadReputationPort.existsForDate(eq("035720"), any(LocalDate.class))).thenReturn(true);
        when(loadArticlePort.findForScoring(eq("005930"), any())).thenReturn(List.of(article()));
        when(loadArticlePort.findForScoring(eq("035420"), any())).thenReturn(List.of());
        when(analyzeSentimentPort.analyze(any(), any())).thenReturn(ArticleSentiment.neutral());
        when(saveReputationPort.saveIfAbsent(any())).thenReturn(true);

        RecalcReputationUseCase.RecalcSummary summary = service.recalcAll();

        assertEquals(3, summary.companies());
        assertEquals(1, summary.saved());
        assertEquals(1, summary.skippedNoArticle());
        assertEquals(1, summary.skippedExisting());
    }
}
