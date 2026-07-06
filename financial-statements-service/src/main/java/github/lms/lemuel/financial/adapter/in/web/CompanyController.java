package github.lms.lemuel.financial.adapter.in.web;

import github.lms.lemuel.financial.adapter.in.web.dto.CompanyResponse;
import github.lms.lemuel.financial.adapter.in.web.dto.FinancialStatementResponse;
import github.lms.lemuel.financial.adapter.in.web.dto.PageResponse;
import github.lms.lemuel.financial.application.port.in.GetCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.GetFinancialStatementsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 코스피 기업 목록/검색 + 기업별 요약 재무제표 공개 조회 API.
 */
@RestController
@RequestMapping("/api/financial/companies")
public class CompanyController {

    private final GetCompaniesUseCase getCompaniesUseCase;
    private final GetFinancialStatementsUseCase getFinancialStatementsUseCase;

    public CompanyController(GetCompaniesUseCase getCompaniesUseCase,
                             GetFinancialStatementsUseCase getFinancialStatementsUseCase) {
        this.getCompaniesUseCase = getCompaniesUseCase;
        this.getFinancialStatementsUseCase = getFinancialStatementsUseCase;
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

    @GetMapping("/{stockCode}/statements")
    public ResponseEntity<List<FinancialStatementResponse>> statements(
            @PathVariable String stockCode,
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer toYear) {
        return ResponseEntity.ok(
                getFinancialStatementsUseCase.byCompany(stockCode, fromYear, toYear).stream()
                        .map(FinancialStatementResponse::from)
                        .toList());
    }
}
