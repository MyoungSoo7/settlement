package github.lms.lemuel.company.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.company.application.port.in.GetArticlesUseCase;
import github.lms.lemuel.company.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.company.application.port.in.GetReputationUseCase;
import github.lms.lemuel.company.domain.Article;
import github.lms.lemuel.company.domain.ArticleSentiment;
import github.lms.lemuel.company.domain.ArticleSource;
import github.lms.lemuel.company.domain.Company;
import github.lms.lemuel.company.domain.IssueCategory;
import github.lms.lemuel.company.domain.ReputationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

    @Mock
    private GetCompaniesUseCase getCompaniesUseCase;
    @Mock
    private GetArticlesUseCase getArticlesUseCase;
    @Mock
    private GetReputationUseCase getReputationUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CompanyController(getCompaniesUseCase, getArticlesUseCase, getReputationUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private Article article() {
        return Article.rehydrate("hash1", "005930", ArticleSource.NAVER_NEWS, "제목", "요약",
                "언론사", "https://news.example.com/1", Instant.parse("2026-07-07T09:00:00Z"));
    }

    private ReputationScore score() {
        return ReputationScore.compute("005930", LocalDate.of(2026, 7, 7), List.of(
                ArticleSentiment.negative(IssueCategory.FINANCIAL), ArticleSentiment.positive()),
                Instant.parse("2026-07-07T09:00:00Z"));
    }

    @Test
    @DisplayName("GET /api/company/companies — 검색 페이지 응답")
    void search() throws Exception {
        when(getCompaniesUseCase.search(isNull(), eq(0), eq(20)))
                .thenReturn(new GetCompaniesUseCase.CompanyPage(
                        List.of(new Company("005930", "00126380", "삼성전자", "KOSPI")), 0, 20, 1));

        mockMvc.perform(get("/api/company/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.content[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("GET /companies/{stockCode} — 존재 시 200")
    void byStockCode() throws Exception {
        when(getCompaniesUseCase.byStockCode("005930"))
                .thenReturn(Optional.of(new Company("005930", "00126380", "삼성전자", "KOSPI")));

        mockMvc.perform(get("/api/company/companies/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"));
    }

    @Test
    @DisplayName("GET /companies/{stockCode} — 미존재 시 404 + message")
    void byStockCodeNotFound() throws Exception {
        when(getCompaniesUseCase.byStockCode("999999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/company/companies/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /articles — source 미지정 목록")
    void articles() throws Exception {
        when(getArticlesUseCase.byCompany(eq("005930"), isNull(), eq(0), eq(20)))
                .thenReturn(new GetArticlesUseCase.ArticlePage(List.of(article()), 0, 20, 1));

        mockMvc.perform(get("/api/company/companies/005930/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("제목"))
                .andExpect(jsonPath("$.content[0].source").value("NAVER_NEWS"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /articles — source 지정 목록")
    void articlesWithSource() throws Exception {
        when(getArticlesUseCase.byCompany(eq("005930"), eq(ArticleSource.NAVER_NEWS), eq(0), eq(20)))
                .thenReturn(new GetArticlesUseCase.ArticlePage(List.of(article()), 0, 20, 1));

        mockMvc.perform(get("/api/company/companies/005930/articles").param("source", "NAVER_NEWS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].publisher").value("언론사"));
    }

    @Test
    @DisplayName("GET /reputation — 스냅샷 존재 시 200")
    void reputation() throws Exception {
        when(getReputationUseCase.current("005930")).thenReturn(Optional.of(score()));

        mockMvc.perform(get("/api/company/companies/005930/reputation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.grade").value("C"))
                .andExpect(jsonPath("$.negativeByCategory.FINANCIAL").value(1));
    }

    @Test
    @DisplayName("GET /reputation — 스냅샷 미산정 시 204")
    void reputationNoContent() throws Exception {
        when(getReputationUseCase.current("005930")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/company/companies/005930/reputation"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /reputation/history — 추이 목록")
    void reputationHistory() throws Exception {
        when(getReputationUseCase.history("005930", 30)).thenReturn(List.of(score()));

        mockMvc.perform(get("/api/company/companies/005930/reputation/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("005930"))
                .andExpect(jsonPath("$[0].score").value(50));
    }
}
