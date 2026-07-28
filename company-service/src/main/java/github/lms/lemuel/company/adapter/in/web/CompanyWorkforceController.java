package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.CompanyWorkforceResponse;
import github.lms.lemuel.company.adapter.in.web.dto.PageResponse;
import github.lms.lemuel.company.application.port.in.GetCompanyWorkforceUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 국민연금 사업장가입자 공개데이터 기반 인원수/추정연봉 조회 (전국 사업장 전체 — 기존
 * {@code /api/company/companies}(상장사 stockCode 체계)와 무관한 독립 검색).
 */
@RestController
@RequestMapping("/api/company/workforce")
public class CompanyWorkforceController {

    private final GetCompanyWorkforceUseCase getCompanyWorkforceUseCase;

    public CompanyWorkforceController(GetCompanyWorkforceUseCase getCompanyWorkforceUseCase) {
        this.getCompanyWorkforceUseCase = getCompanyWorkforceUseCase;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CompanyWorkforceResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        GetCompanyWorkforceUseCase.WorkforcePage result = getCompanyWorkforceUseCase.search(name, page, size);
        return ResponseEntity.ok(new PageResponse<>(
                result.content().stream().map(CompanyWorkforceResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages()));
    }
}
