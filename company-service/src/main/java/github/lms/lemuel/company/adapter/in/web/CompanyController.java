package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.ArticleResponse;
import github.lms.lemuel.company.adapter.in.web.dto.CompanyResponse;
import github.lms.lemuel.company.adapter.in.web.dto.PageResponse;
import github.lms.lemuel.company.adapter.in.web.dto.ReputationResponse;
import github.lms.lemuel.company.application.port.in.GetArticlesUseCase;
import github.lms.lemuel.company.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.company.application.port.in.GetReputationUseCase;
import github.lms.lemuel.company.domain.ArticleSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 기업 목록/검색 + 기업별 뉴스 기사 공개 조회 API.
 */
@RestController
@RequestMapping("/api/company/companies")
public class CompanyController {

    private final GetCompaniesUseCase getCompaniesUseCase;
    private final GetArticlesUseCase getArticlesUseCase;
    private final GetReputationUseCase getReputationUseCase;

    public CompanyController(GetCompaniesUseCase getCompaniesUseCase,
                             GetArticlesUseCase getArticlesUseCase,
                             GetReputationUseCase getReputationUseCase) {
        this.getCompaniesUseCase = getCompaniesUseCase;
        this.getArticlesUseCase = getArticlesUseCase;
        this.getReputationUseCase = getReputationUseCase;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CompanyResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        GetCompaniesUseCase.CompanyPage result = getCompaniesUseCase.search(keyword, page, size);
        return ResponseEntity.ok(new PageResponse<>(
                result.content().stream().map(CompanyResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages()));
    }

    @GetMapping("/{stockCode}")
    public ResponseEntity<CompanyResponse> byStockCode(@PathVariable String stockCode) {
        return getCompaniesUseCase.byStockCode(stockCode)
                .map(company -> ResponseEntity.ok(CompanyResponse.from(company)))
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
    }

    @GetMapping("/{stockCode}/articles")
    public ResponseEntity<PageResponse<ArticleResponse>> articles(
            @PathVariable String stockCode,
            @RequestParam(required = false) ArticleSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        GetArticlesUseCase.ArticlePage result = getArticlesUseCase.byCompany(stockCode, source, page, size);
        return ResponseEntity.ok(new PageResponse<>(
                result.content().stream().map(ArticleResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages()));
    }

    /** 최신 평판 스냅샷 — 아직 산정 전이면 204. */
    @GetMapping("/{stockCode}/reputation")
    public ResponseEntity<ReputationResponse> reputation(@PathVariable String stockCode) {
        return getReputationUseCase.current(stockCode)
                .map(ReputationResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 평판 스냅샷 추이 (snapshotDate 내림차순). */
    @GetMapping("/{stockCode}/reputation/history")
    public ResponseEntity<List<ReputationResponse>> reputationHistory(
            @PathVariable String stockCode,
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(
                getReputationUseCase.history(stockCode, limit).stream()
                        .map(ReputationResponse::from)
                        .toList());
    }
}
