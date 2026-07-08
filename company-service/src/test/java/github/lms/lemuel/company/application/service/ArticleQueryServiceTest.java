package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetArticlesUseCase;
import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleQueryServiceTest {

    private final LoadCompanyPort loadCompanyPort = mock(LoadCompanyPort.class);
    private final LoadArticlePort loadArticlePort = mock(LoadArticlePort.class);
    private final ArticleQueryService service = new ArticleQueryService(loadCompanyPort, loadArticlePort);

    @Test
    @DisplayName("존재하지 않는 기업의 기사 조회는 404 로 이어지는 예외를 낸다")
    void rejectsUnknownCompany() {
        when(loadCompanyPort.findByStockCode("999999")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.byCompany("999999", null, 0, 20));
    }

    @Test
    @DisplayName("페이징을 보정해 포트로 위임한다")
    void delegatesWithClampedPaging() {
        when(loadCompanyPort.findByStockCode("005930"))
                .thenReturn(Optional.of(new Company("005930", null, "삼성전자", null)));
        when(loadArticlePort.findByCompany("005930", ArticleSource.NAVER_NEWS, 0, 100))
                .thenReturn(new LoadArticlePort.PageResult(List.of(), 0));

        GetArticlesUseCase.ArticlePage result = service.byCompany("005930", ArticleSource.NAVER_NEWS, -1, 500);

        assertEquals(0, result.page());
        assertEquals(100, result.size());
        verify(loadArticlePort).findByCompany("005930", ArticleSource.NAVER_NEWS, 0, 100);
    }
}
