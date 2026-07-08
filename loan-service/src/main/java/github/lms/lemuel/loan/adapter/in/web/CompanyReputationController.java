package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.loan.application.port.in.GetCompanyReputationUseCase;
import github.lms.lemuel.loan.domain.CompanyReputation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 셀러(법인) 평판 프로젝션 조회 API — company 이벤트로 적재된 로컬 리스크 지표 (ADR 0023 Phase 3).
 */
@RestController
@RequestMapping("/loans/company-reputation")
public class CompanyReputationController {

    private final GetCompanyReputationUseCase getCompanyReputationUseCase;

    public CompanyReputationController(GetCompanyReputationUseCase getCompanyReputationUseCase) {
        this.getCompanyReputationUseCase = getCompanyReputationUseCase;
    }

    /** 아직 이벤트를 받지 못한 종목이면 204. */
    @GetMapping("/{stockCode}")
    public ResponseEntity<CompanyReputationResponse> byStockCode(@PathVariable String stockCode) {
        return getCompanyReputationUseCase.byStockCode(stockCode)
                .map(CompanyReputationResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record CompanyReputationResponse(String stockCode, int score, String grade,
                                            String previousGrade, LocalDate snapshotDate) {
        static CompanyReputationResponse from(CompanyReputation r) {
            return new CompanyReputationResponse(r.getStockCode(), r.getScore(), r.getGrade(),
                    r.getPreviousGrade(), r.getSnapshotDate());
        }
    }
}
