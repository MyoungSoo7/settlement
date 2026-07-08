package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.GetArticlesUseCase;
import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.ArticleSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
@Transactional(readOnly = true)
public class ArticleQueryService implements GetArticlesUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    private final LoadCompanyPort loadCompanyPort;
    private final LoadArticlePort loadArticlePort;

    public ArticleQueryService(LoadCompanyPort loadCompanyPort, LoadArticlePort loadArticlePort) {
        this.loadCompanyPort = loadCompanyPort;
        this.loadArticlePort = loadArticlePort;
    }

    @Override
    public ArticlePage byCompany(String stockCode, ArticleSource source, int page, int size) {
        loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LoadArticlePort.PageResult result = loadArticlePort.findByCompany(stockCode, source, safePage, safeSize);
        return new ArticlePage(result.content(), safePage, safeSize, result.totalElements());
    }
}
