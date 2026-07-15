package github.lms.lemuel.company.adapter.in.web;

import github.lms.lemuel.company.adapter.in.web.dto.CompanyRegistrationRequest;
import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase;
import github.lms.lemuel.company.application.port.in.RegisterCompaniesUseCase.RegisterResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기업 마스터 일괄 등록 (운영자 전용 — AdminApiKeyFilter 게이팅, gateway 미라우팅).
 *
 * <p>상장사 전체 브리핑 배치의 선행 단계: 업로드 대상 기업(stockCode)을 먼저 등록해 둔다
 * (미등록이면 문서 업로드가 404). 같은 stockCode 재등록은 교체 — 재실행 멱등.
 */
@RestController
@RequestMapping("/admin/company/companies")
public class CompanyRegistrationAdminController {

    private final RegisterCompaniesUseCase registerCompaniesUseCase;

    public CompanyRegistrationAdminController(RegisterCompaniesUseCase registerCompaniesUseCase) {
        this.registerCompaniesUseCase = registerCompaniesUseCase;
    }

    @PostMapping
    public ResponseEntity<RegisterResult> register(@RequestBody CompanyRegistrationRequest request) {
        return ResponseEntity.ok(registerCompaniesUseCase.register(request.toCommands()));
    }
}
