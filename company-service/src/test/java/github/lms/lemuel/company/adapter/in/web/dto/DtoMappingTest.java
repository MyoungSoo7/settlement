package github.lms.lemuel.company.adapter.in.web.dto;

import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.CompanyDocument;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoMappingTest {

    @Test
    @DisplayName("CompanyResponse.from")
    void companyResponse() {
        CompanyResponse response = CompanyResponse.from(new Company("005930", "00126380", "삼성전자", "KOSPI"));
        assertEquals("005930", response.stockCode());
        assertEquals("00126380", response.corpCode());
        assertEquals("삼성전자", response.name());
        assertEquals("KOSPI", response.market());
    }

    @Test
    @DisplayName("ArticleResponse.from")
    void articleResponse() {
        Article article = Article.rehydrate("h", "005930", ArticleSource.NAVER_NEWS, "제목", "요약",
                "언론사", "https://news.example.com/1", Instant.parse("2026-07-07T09:00:00Z"));
        ArticleResponse response = ArticleResponse.from(article);
        assertEquals("제목", response.title());
        assertEquals("요약", response.summary());
        assertEquals("언론사", response.publisher());
        assertEquals("https://news.example.com/1", response.url());
        assertEquals("NAVER_NEWS", response.source());
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), response.publishedAt());
    }

    @Test
    @DisplayName("CompanyDocumentResponse.from")
    void companyDocumentResponse() {
        CompanyDocument document = CompanyDocument.rehydrate(5L, "005930", "브리핑", "b.docx",
                "application/pdf", 42, Instant.parse("2026-07-07T09:00:00Z"));
        CompanyDocumentResponse response = CompanyDocumentResponse.from(document);
        assertEquals(5L, response.id());
        assertEquals("005930", response.stockCode());
        assertEquals("브리핑", response.title());
        assertEquals("b.docx", response.fileName());
        assertEquals(42, response.sizeBytes());
    }

    @Test
    @DisplayName("ReputationResponse.from — 부정 카테고리는 count>0 만 포함")
    void reputationResponse() {
        ReputationScore score = ReputationScore.compute("005930", LocalDate.of(2026, 7, 7), List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL),
                ArticleSentiment.negative(IssueCategory.LEGAL),
                ArticleSentiment.positive()), Instant.parse("2026-07-07T09:00:00Z"));

        ReputationResponse response = ReputationResponse.from(score);

        assertEquals("005930", response.stockCode());
        assertEquals(LocalDate.of(2026, 7, 7), response.snapshotDate());
        assertEquals(score.grade().name(), response.grade());
        assertEquals(3, response.articleCount());
        assertEquals(1, response.positiveCount());
        assertEquals(2, response.negativeCount());
        assertTrue(response.negativeByCategory().containsKey("FINANCIAL"));
        assertTrue(response.negativeByCategory().containsKey("LEGAL"));
        assertFalse(response.negativeByCategory().containsKey("PRODUCT"));
    }

    @Test
    @DisplayName("PageResponse 레코드 접근자")
    void pageResponse() {
        PageResponse<String> page = new PageResponse<>(List.of("a", "b"), 1, 20, 42, 3);
        assertEquals(2, page.content().size());
        assertEquals(1, page.page());
        assertEquals(20, page.size());
        assertEquals(42, page.totalElements());
        assertEquals(3, page.totalPages());
    }
}
