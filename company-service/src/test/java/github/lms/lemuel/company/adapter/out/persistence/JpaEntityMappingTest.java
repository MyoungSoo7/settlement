package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.CompanyDocument;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JpaEntityMappingTest {

    @Test
    @DisplayName("ArticleJpaEntity fromDomain/toDomain 왕복 매핑")
    void articleRoundTrip() {
        Article article = Article.collect("005930", ArticleSource.NAVER_NEWS, "제목", "요약",
                "언론사", "https://news.example.com/1", Instant.parse("2026-07-07T09:00:00Z"));

        Article back = ArticleJpaEntity.fromDomain(article).toDomain();

        assertEquals(article.urlHash(), back.urlHash());
        assertEquals("005930", back.stockCode());
        assertEquals(ArticleSource.NAVER_NEWS, back.source());
        assertEquals("제목", back.title());
        assertEquals("요약", back.summary());
        assertEquals("언론사", back.publisher());
        assertEquals("https://news.example.com/1", back.url());
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), back.publishedAt());
    }

    @Test
    @DisplayName("ReputationScoreJpaEntity fromDomain/toDomain — 카테고리별 부정 건수 보존")
    void reputationRoundTrip() {
        ReputationScore score = ReputationScore.compute("005930", LocalDate.of(2026, 7, 7), List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL),
                ArticleSentiment.negative(IssueCategory.LEGAL),
                ArticleSentiment.positive(),
                ArticleSentiment.neutral()), Instant.parse("2026-07-07T09:00:00Z"));

        ReputationScore back = ReputationScoreJpaEntity.fromDomain(score).toDomain();

        assertEquals("005930", back.stockCode());
        assertEquals(LocalDate.of(2026, 7, 7), back.snapshotDate());
        assertEquals(score.score(), back.score());
        assertEquals(score.grade(), back.grade());
        assertEquals(4, back.articleCount());
        assertEquals(1, back.positiveCount());
        assertEquals(2, back.negativeCount());
        assertEquals(1, back.neutralCount());
        assertEquals(1, back.negativeCountOf(IssueCategory.FINANCIAL));
        assertEquals(1, back.negativeCountOf(IssueCategory.LEGAL));
        // 없던 카테고리는 0 (putIfPositive 가 넣지 않음)
        assertEquals(0, back.negativeCountOf(IssueCategory.PRODUCT));
    }

    @Test
    @DisplayName("CompanyDocumentJpaEntity fromDomain/replaceWith/toDomain 및 content 접근")
    void documentRoundTrip() {
        CompanyDocument document = CompanyDocument.create("005930", "브리핑", "briefing.docx",
                1234, Instant.parse("2026-07-07T09:00:00Z"));
        byte[] content = {1, 2, 3, 4};

        CompanyDocumentJpaEntity entity = CompanyDocumentJpaEntity.fromDomain(document, content);
        CompanyDocument back = entity.toDomain();

        assertEquals("005930", back.stockCode());
        assertEquals("브리핑", back.title());
        assertEquals("briefing.docx", back.fileName());
        assertEquals(document.contentType(), back.contentType());
        assertEquals(1234, back.sizeBytes());
        assertEquals(Instant.parse("2026-07-07T09:00:00Z"), back.uploadedAt());
        assertArrayEquals(content, entity.content());

        // 교체 시맨틱 — 같은 엔티티에 새 내용/제목을 덮어쓴다
        CompanyDocument replacement = CompanyDocument.create("005930", "새 브리핑", "briefing.docx",
                5678, Instant.parse("2026-07-08T09:00:00Z"));
        byte[] newContent = {9, 9};
        entity.replaceWith(replacement, newContent);

        assertEquals("새 브리핑", entity.toDomain().title());
        assertEquals(5678, entity.toDomain().sizeBytes());
        assertArrayEquals(newContent, entity.content());
    }

    @Test
    @DisplayName("CompanyJpaEntity toDomain 매핑")
    void companyToDomain() throws Exception {
        CompanyJpaEntity entity = new CompanyJpaEntity();
        set(entity, "stockCode", "005930");
        set(entity, "corpCode", "00126380");
        set(entity, "name", "삼성전자");
        set(entity, "market", "KOSPI");
        set(entity, "updatedAt", Instant.parse("2026-07-07T09:00:00Z"));

        Company company = entity.toDomain();

        assertEquals("005930", company.stockCode());
        assertEquals("00126380", company.corpCode());
        assertEquals("삼성전자", company.name());
        assertEquals("KOSPI", company.market());
    }

    @Test
    @DisplayName("CompanySeller/CompanySellerLink 엔티티 생성자·게터")
    void sellerEntities() {
        Instant now = Instant.parse("2026-07-07T09:00:00Z");
        CompanySellerJpaEntity seller = new CompanySellerJpaEntity(7L, "a@b.com", now);
        assertEquals(7L, seller.getSellerId());
        assertEquals("a@b.com", seller.getEmail());
        assertEquals(now, seller.getUpdatedAt());

        CompanySellerLinkJpaEntity link = new CompanySellerLinkJpaEntity(7L, "005930", now);
        assertEquals(7L, link.getSellerId());
        assertEquals("005930", link.getStockCode());
        assertEquals(now, link.getLinkedAt());
    }

    @Test
    @DisplayName("CompanyDocumentMeta toDomain 매핑")
    void metaToDomain() {
        CompanyDocumentMeta meta = new CompanyDocumentMeta(3L, "005930", "제목", "f.pdf",
                "application/pdf", 42, Instant.parse("2026-07-07T09:00:00Z"));

        CompanyDocument document = meta.toDomain();

        assertEquals(3L, document.id());
        assertEquals("005930", document.stockCode());
        assertEquals("f.pdf", document.fileName());
        assertEquals("application/pdf", document.contentType());
        assertEquals(42, document.sizeBytes());
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
