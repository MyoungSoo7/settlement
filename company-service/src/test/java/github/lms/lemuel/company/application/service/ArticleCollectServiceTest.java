package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.CollectResult;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.NewsClientPort;
import github.lms.lemuel.company.application.port.out.SaveArticlePort;
import github.lms.lemuel.company.domain.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import github.lms.lemuel.company.domain.Company;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleCollectServiceTest {

    private final NewsClientPort newsClientPort = mock(NewsClientPort.class);
    private final LoadCompanyPort loadCompanyPort = mock(LoadCompanyPort.class);
    private final SaveArticlePort saveArticlePort = mock(SaveArticlePort.class);
    private final ArticleCollectService service =
            new ArticleCollectService(newsClientPort, loadCompanyPort, saveArticlePort, 0);

    private static final Company SAMSUNG = new Company("005930", null, "삼성전자", null);

    @BeforeEach
    void configured() {
        when(newsClientPort.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("API 자격증명 미설정이면 409 로 이어지는 예외를 내고 아무것도 하지 않는다")
    void rejectsWhenNotConfigured() {
        when(newsClientPort.isConfigured()).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.collectFor("005930"));
        verify(newsClientPort, never()).fetchNews(anyString());
    }

    @Test
    @DisplayName("존재하지 않는 기업 수집 요청은 거부한다")
    void rejectsUnknownCompany() {
        when(loadCompanyPort.findByStockCode("999999")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.collectFor("999999"));
    }

    @Test
    @DisplayName("유효 항목은 도메인으로 변환해 저장하고, 잘못된 항목은 스킵하며, 중복 수를 집계한다")
    void collectsAndCountsDuplicates() {
        when(loadCompanyPort.findByStockCode("005930")).thenReturn(Optional.of(SAMSUNG));
        when(newsClientPort.fetchNews("삼성전자")).thenReturn(List.of(
                new NewsClientPort.NewsItem("정상 기사", "요약", "a.com", "https://a.com/1", Instant.now()),
                new NewsClientPort.NewsItem("URL 불량", "요약", null, "not-a-url", Instant.now()),
                new NewsClientPort.NewsItem("이미 수집됨", "요약", "a.com", "https://a.com/2", Instant.now())));
        when(saveArticlePort.saveNew(anyList())).thenReturn(1);   // 2건 중 1건만 신규

        CollectResult result = service.collectFor("005930");

        assertEquals(new CollectResult(1, 3, 1, 1), result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Article>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveArticlePort).saveNew(captor.capture());
        assertEquals(2, captor.getValue().size());   // URL 불량 1건은 도메인 진입 전에 스킵
    }

    @Test
    @DisplayName("전체 수집은 기업별 결과를 합산한다")
    void collectAllMergesResults() {
        when(loadCompanyPort.findAll()).thenReturn(List.of(
                SAMSUNG, new Company("035420", null, "NAVER", null)));
        when(newsClientPort.fetchNews(anyString())).thenReturn(List.of(
                new NewsClientPort.NewsItem("기사", null, "a.com", "https://a.com/1", null)));
        when(saveArticlePort.saveNew(anyList())).thenReturn(1);

        CollectResult result = service.collectAll();

        assertEquals(new CollectResult(2, 2, 2, 0), result);
    }
}
