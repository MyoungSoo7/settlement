package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadArticlePort;
import github.lms.lemuel.company.application.port.out.LoadCompanyDocumentPort;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.CompanyDocument;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistenceAdaptersTest {

    private static Article article(String urlHash) {
        return Article.rehydrate(urlHash, "005930", ArticleSource.NAVER_NEWS, "제목", "요약",
                "언론사", "https://news.example.com/" + urlHash, Instant.parse("2026-07-07T09:00:00Z"));
    }

    @Nested
    class ArticleAdapter {

        private final ArticleRepository repository = mock(ArticleRepository.class);
        private final ArticlePersistenceAdapter adapter = new ArticlePersistenceAdapter(repository);

        @Test
        @DisplayName("findByCompany — source null 이면 stockCode 전체 조회")
        void findAllSources() {
            Page<ArticleJpaEntity> page = new PageImpl<>(
                    List.of(ArticleJpaEntity.fromDomain(article("h1"))), PageRequest.of(0, 20), 1);
            when(repository.findByStockCode(eq("005930"), any())).thenReturn(page);

            LoadArticlePort.PageResult result = adapter.findByCompany("005930", null, 0, 20);

            assertEquals(1, result.content().size());
            assertEquals(1, result.totalElements());
            verify(repository, never()).findByStockCodeAndSource(any(), any(), any());
        }

        @Test
        @DisplayName("findByCompany — source 지정 시 source 필터 조회")
        void findBySource() {
            Page<ArticleJpaEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(repository.findByStockCodeAndSource(eq("005930"), eq(ArticleSource.NAVER_NEWS), any()))
                    .thenReturn(page);

            LoadArticlePort.PageResult result = adapter.findByCompany("005930", ArticleSource.NAVER_NEWS, 0, 20);

            assertTrue(result.content().isEmpty());
            verify(repository, never()).findByStockCode(any(), any());
        }

        @Test
        @DisplayName("findForScoring — 엔티티를 도메인으로 매핑")
        void findForScoring() {
            Instant since = Instant.parse("2026-07-01T00:00:00Z");
            when(repository.findForScoring("005930", since))
                    .thenReturn(List.of(ArticleJpaEntity.fromDomain(article("h2"))));

            List<Article> result = adapter.findForScoring("005930", since);

            assertEquals(1, result.size());
            assertEquals("005930", result.get(0).stockCode());
        }

        @Test
        @DisplayName("saveNew — 배치 내 중복·기존 중복·UNIQUE 충돌을 건너뛰고 저장 건수만 센다")
        void saveNew() {
            Article a = article("dup");   // 배치에 2번
            Article b = article("exists"); // DB 에 이미 존재
            Article c = article("race");   // 저장 중 UNIQUE 충돌
            Article d = article("ok");     // 정상 저장

            when(repository.existsByUrlHash(a.urlHash())).thenReturn(false);
            when(repository.existsByUrlHash(b.urlHash())).thenReturn(true);
            when(repository.existsByUrlHash(c.urlHash())).thenReturn(false);
            when(repository.existsByUrlHash(d.urlHash())).thenReturn(false);
            when(repository.save(any())).thenAnswer(inv -> {
                ArticleJpaEntity e = inv.getArgument(0);
                if (c.urlHash().equals(urlHashOf(e))) {
                    throw new DataIntegrityViolationException("dup");
                }
                return e;
            });

            int saved = adapter.saveNew(List.of(a, a, b, c, d));

            // a(1회만) + d = 2 저장, a 중복/b 기존/c 충돌 스킵
            assertEquals(2, saved);
        }

        private String urlHashOf(ArticleJpaEntity e) {
            try {
                Field f = ArticleJpaEntity.class.getDeclaredField("urlHash");
                f.setAccessible(true);
                return (String) f.get(e);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Nested
    class CompanyAdapter {

        private final CompanyRepository repository = mock(CompanyRepository.class);
        private final CompanyPersistenceAdapter adapter = new CompanyPersistenceAdapter(repository);

        private CompanyJpaEntity entity() throws Exception {
            CompanyJpaEntity e = new CompanyJpaEntity();
            for (var pair : List.of(new String[]{"stockCode", "005930"}, new String[]{"corpCode", "00126380"},
                    new String[]{"name", "삼성전자"}, new String[]{"market", "KOSPI"})) {
                Field f = CompanyJpaEntity.class.getDeclaredField(pair[0]);
                f.setAccessible(true);
                f.set(e, pair[1]);
            }
            Field u = CompanyJpaEntity.class.getDeclaredField("updatedAt");
            u.setAccessible(true);
            u.set(e, Instant.parse("2026-07-07T09:00:00Z"));
            return e;
        }

        @Test
        @DisplayName("search — keyword null 이면 findAll, 아니면 search")
        void search() throws Exception {
            Page<CompanyJpaEntity> page = new PageImpl<>(List.of(entity()), PageRequest.of(0, 20), 1);
            when(repository.findAll(any(PageRequest.class))).thenReturn(page);

            LoadCompanyPort.SearchResult all = adapter.search(null, 0, 20);
            assertEquals(1, all.content().size());
            assertEquals("005930", all.content().get(0).stockCode());
            verify(repository, never()).search(any(), any());

            when(repository.search(eq("삼성"), any())).thenReturn(page);
            LoadCompanyPort.SearchResult keyword = adapter.search("삼성", 0, 20);
            assertEquals(1, keyword.content().size());
        }

        @Test
        @DisplayName("findByStockCode 위임")
        void findByStockCode() throws Exception {
            when(repository.findById("005930")).thenReturn(Optional.of(entity()));
            Optional<Company> found = adapter.findByStockCode("005930");
            assertTrue(found.isPresent());
            assertEquals("삼성전자", found.get().name());
        }

        @Test
        @DisplayName("findAll 정렬 위임")
        void findAll() throws Exception {
            when(repository.findAll(any(org.springframework.data.domain.Sort.class)))
                    .thenReturn(List.of(entity()));
            assertEquals(1, adapter.findAll().size());
        }

        @Test
        @DisplayName("upsertAll — 신규는 registered, 기존은 updated, 제약 위반은 항목만 skip")
        void upsertAll() {
            when(repository.existsById("005930")).thenReturn(false); // 신규
            when(repository.existsById("035420")).thenReturn(true);  // 기존 → 갱신
            when(repository.existsById("000660")).thenReturn(false); // 신규지만 corpCode 충돌
            when(repository.saveAndFlush(any())).thenAnswer(inv -> {
                CompanyJpaEntity e = inv.getArgument(0);
                if ("000660".equals(stockCodeOf(e))) {
                    throw new DataIntegrityViolationException("corp_code unique");
                }
                return e;
            });

            var result = adapter.upsertAll(List.of(
                    new Company("005930", "00126380", "삼성전자", "KOSPI"),
                    new Company("035420", "00266961", "NAVER", "KOSPI"),
                    new Company("000660", "00164779", "SK하이닉스", "KOSPI")));

            assertEquals(1, result.registered());
            assertEquals(1, result.updated());
            assertEquals(1, result.skipped());
            verify(repository, org.mockito.Mockito.times(3)).saveAndFlush(any());
        }

        private String stockCodeOf(CompanyJpaEntity e) {
            try {
                Field f = CompanyJpaEntity.class.getDeclaredField("stockCode");
                f.setAccessible(true);
                return (String) f.get(e);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Nested
    class DocumentAdapter {

        private final CompanyDocumentRepository repository = mock(CompanyDocumentRepository.class);
        private final CompanyDocumentPersistenceAdapter adapter =
                new CompanyDocumentPersistenceAdapter(repository);

        private CompanyDocument document() {
            return CompanyDocument.create("005930", "브리핑", "briefing.docx",
                    100, Instant.parse("2026-07-07T09:00:00Z"));
        }

        @Test
        @DisplayName("saveOrReplace — 신규(미존재)면 fromDomain 저장")
        void saveNew() {
            when(repository.findByStockCodeAndFileName("005930", "briefing.docx"))
                    .thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CompanyDocument saved = adapter.saveOrReplace(document(), new byte[]{1});
            assertEquals("briefing.docx", saved.fileName());
        }

        @Test
        @DisplayName("saveOrReplace — 기존 있으면 교체 후 저장")
        void replaceExisting() {
            CompanyDocumentJpaEntity existing = CompanyDocumentJpaEntity.fromDomain(
                    CompanyDocument.create("005930", "옛제목", "briefing.docx", 50,
                            Instant.parse("2026-07-06T09:00:00Z")), new byte[]{0});
            when(repository.findByStockCodeAndFileName("005930", "briefing.docx"))
                    .thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            CompanyDocument saved = adapter.saveOrReplace(document(), new byte[]{9});
            assertEquals("브리핑", saved.title());
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("saveOrReplace — UNIQUE 충돌은 IllegalStateException")
        void conflict() {
            when(repository.findByStockCodeAndFileName(any(), any())).thenReturn(Optional.empty());
            when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

            assertThrows(IllegalStateException.class, () -> adapter.saveOrReplace(document(), new byte[]{1}));
        }

        @Test
        @DisplayName("findByStockCode — 메타 프로젝션 매핑")
        void findByStockCode() {
            when(repository.findMetaByStockCode("005930")).thenReturn(List.of(
                    new CompanyDocumentMeta(1L, "005930", "제목", "f.pdf", "application/pdf", 10,
                            Instant.parse("2026-07-07T09:00:00Z"))));

            List<CompanyDocument> result = adapter.findByStockCode("005930");
            assertEquals(1, result.size());
            assertEquals("f.pdf", result.get(0).fileName());
        }

        @Test
        @DisplayName("findWithContent — 엔티티 + BYTEA 반환, 미존재면 empty")
        void findWithContent() {
            CompanyDocumentJpaEntity entity = CompanyDocumentJpaEntity.fromDomain(document(), new byte[]{7, 8});
            when(repository.findById(1L)).thenReturn(Optional.of(entity));
            when(repository.findById(2L)).thenReturn(Optional.empty());

            Optional<LoadCompanyDocumentPort.DocumentContent> found = adapter.findWithContent(1L);
            assertTrue(found.isPresent());
            assertEquals("briefing.docx", found.get().document().fileName());
            assertEquals(2, found.get().content().length);

            assertTrue(adapter.findWithContent(2L).isEmpty());
        }
    }

    @Nested
    class ReputationAdapter {

        private final ReputationScoreRepository repository = mock(ReputationScoreRepository.class);
        private final ReputationPersistenceAdapter adapter = new ReputationPersistenceAdapter(repository);

        private ReputationScore score(LocalDate date) {
            return ReputationScore.compute("005930", date, List.of(
                    ArticleSentiment.negative(IssueCategory.FINANCIAL), ArticleSentiment.positive()),
                    Instant.parse("2026-07-07T09:00:00Z"));
        }

        @Test
        @DisplayName("findLatest 위임")
        void findLatest() {
            when(repository.findFirstByStockCodeOrderByCalculatedAtDesc("005930"))
                    .thenReturn(Optional.of(ReputationScoreJpaEntity.fromDomain(score(LocalDate.of(2026, 7, 7)))));

            Optional<ReputationScore> latest = adapter.findLatest("005930");
            assertTrue(latest.isPresent());
            assertEquals(50, latest.get().score());
        }

        @Test
        @DisplayName("findHistory — Page 를 content 로 변환")
        void findHistory() {
            Page<ReputationScoreJpaEntity> page = new PageImpl<>(
                    List.of(ReputationScoreJpaEntity.fromDomain(score(LocalDate.of(2026, 7, 7)))));
            when(repository.findByStockCodeOrderBySnapshotDateDesc(eq("005930"), any())).thenReturn(page);

            List<ReputationScore> history = adapter.findHistory("005930", 30);
            assertEquals(1, history.size());
        }

        @Test
        @DisplayName("existsForDate 위임")
        void existsForDate() {
            when(repository.existsByStockCodeAndSnapshotDate("005930", LocalDate.of(2026, 7, 7)))
                    .thenReturn(true);
            assertTrue(adapter.existsForDate("005930", LocalDate.of(2026, 7, 7)));
        }

        @Test
        @DisplayName("saveIfAbsent — 이미 오늘자 스냅샷 있으면 저장 없이 false")
        void saveIfAbsentSkipsExisting() {
            when(repository.existsByStockCodeAndSnapshotDate("005930", LocalDate.of(2026, 7, 7)))
                    .thenReturn(true);

            assertFalse(adapter.saveIfAbsent(score(LocalDate.of(2026, 7, 7))));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("saveIfAbsent — 신규면 저장 후 true")
        void saveIfAbsentSaves() {
            when(repository.existsByStockCodeAndSnapshotDate("005930", LocalDate.of(2026, 7, 7)))
                    .thenReturn(false);

            assertTrue(adapter.saveIfAbsent(score(LocalDate.of(2026, 7, 7))));
            verify(repository).save(any());
        }

        @Test
        @DisplayName("saveIfAbsent — 동시 저장 UNIQUE 충돌은 false 로 흡수")
        void saveIfAbsentAbsorbsRace() {
            when(repository.existsByStockCodeAndSnapshotDate("005930", LocalDate.of(2026, 7, 7)))
                    .thenReturn(false);
            doThrow(new DataIntegrityViolationException("dup")).when(repository).save(any());

            assertFalse(adapter.saveIfAbsent(score(LocalDate.of(2026, 7, 7))));
        }
    }

    @Nested
    class SellerAdapter {

        private final CompanySellerRepository sellerRepository = mock(CompanySellerRepository.class);
        private final CompanySellerLinkRepository linkRepository = mock(CompanySellerLinkRepository.class);
        private final SellerPersistenceAdapter adapter =
                new SellerPersistenceAdapter(sellerRepository, linkRepository);

        @Test
        @DisplayName("record — 셀러 저장")
        void record() {
            adapter.record(7L, "a@b.com");
            verify(sellerRepository).save(any(CompanySellerJpaEntity.class));
        }

        @Test
        @DisplayName("link — 링크 저장")
        void link() {
            adapter.link(7L, "005930");
            verify(linkRepository).save(any(CompanySellerLinkJpaEntity.class));
        }

        @Test
        @DisplayName("sellersOf — 링크에서 sellerId 만 추출")
        void sellersOf() {
            when(linkRepository.findByStockCode("005930")).thenReturn(List.of(
                    new CompanySellerLinkJpaEntity(7L, "005930", Instant.parse("2026-07-07T09:00:00Z")),
                    new CompanySellerLinkJpaEntity(9L, "005930", Instant.parse("2026-07-07T09:00:00Z"))));

            List<Long> sellers = adapter.sellersOf("005930");
            assertEquals(List.of(7L, 9L), sellers);
        }
    }
}
