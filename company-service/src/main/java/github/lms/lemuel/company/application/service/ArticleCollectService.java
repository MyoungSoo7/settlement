package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.CollectArticlesUseCase;
import github.lms.lemuel.company.application.port.in.CollectResult;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.NewsClientPort;
import github.lms.lemuel.company.application.port.out.SaveArticlePort;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 뉴스 수집 유스케이스 구현.
 *
 * <p>멱등 1차 방어는 {@link Article} 의 urlHash, 최종 방어는 DB UNIQUE — 같은 트리거를 두 번
 * 눌러도 saved 만 0 이 될 뿐 실패하지 않는다. 외부 API 쿼터 보호를 위해 기업 간
 * {@code request-interval-ms} 간격을 둔다(가상 스레드에서 sleep — collectTaskExecutor 참조).
 */
@Service
public class ArticleCollectService implements CollectArticlesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ArticleCollectService.class);

    private final NewsClientPort newsClientPort;
    private final LoadCompanyPort loadCompanyPort;
    private final SaveArticlePort saveArticlePort;
    private final long requestIntervalMs;

    public ArticleCollectService(NewsClientPort newsClientPort,
                                 LoadCompanyPort loadCompanyPort,
                                 SaveArticlePort saveArticlePort,
                                 @Value("${app.company.collect.request-interval-ms:200}") long requestIntervalMs) {
        this.newsClientPort = newsClientPort;
        this.loadCompanyPort = loadCompanyPort;
        this.saveArticlePort = saveArticlePort;
        this.requestIntervalMs = requestIntervalMs;
    }

    @Override
    public CollectResult collectFor(String stockCode) {
        requireConfigured();
        Company company = loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        return collectOne(company);
    }

    @Override
    public CollectResult collectAll() {
        requireConfigured();
        CollectResult total = new CollectResult(0, 0, 0, 0);
        List<Company> companies = loadCompanyPort.findAll();
        for (int i = 0; i < companies.size(); i++) {
            if (i > 0) {
                pause();
            }
            total = CollectResult.merge(total, collectOne(companies.get(i)));
        }
        return total;
    }

    private CollectResult collectOne(Company company) {
        List<NewsClientPort.NewsItem> items = newsClientPort.fetchNews(company.name());
        List<Article> articles = new ArrayList<>(items.size());
        for (NewsClientPort.NewsItem item : items) {
            try {
                articles.add(Article.collect(company.stockCode(), ArticleSource.NAVER_NEWS,
                        item.title(), item.summary(), item.publisher(), item.url(), item.publishedAt()));
            } catch (IllegalArgumentException e) {
                log.warn("기사 항목 스킵 stockCode={} url={} — {}", company.stockCode(), item.url(), e.getMessage());
            }
        }
        int saved = saveArticlePort.saveNew(articles);
        log.info("뉴스 수집 stockCode={} name={} fetched={} saved={} duplicated={}",
                company.stockCode(), company.name(), items.size(), saved, articles.size() - saved);
        return new CollectResult(1, items.size(), saved, articles.size() - saved);
    }

    private void requireConfigured() {
        if (!newsClientPort.isConfigured()) {
            throw new IllegalStateException("네이버 뉴스 API 자격증명 미설정 — NAVER_CLIENT_ID/SECRET 을 설정하세요");
        }
    }

    private void pause() {
        try {
            Thread.sleep(requestIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단되었습니다", e);
        }
    }
}
