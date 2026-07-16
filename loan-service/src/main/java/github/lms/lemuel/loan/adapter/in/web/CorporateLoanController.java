package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.loan.adapter.in.web.dto.CorporateCreditResponse;
import github.lms.lemuel.loan.adapter.in.web.dto.CorporateLoanRequestBody;
import github.lms.lemuel.loan.adapter.in.web.dto.CorporateLoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase.RequestCorporateLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 기업(코스피/코스닥 상장사) 신용대출 — CEO 메뉴용 신용평가/신청/실행/조회 API.
 */
@RestController
@RequestMapping("/loans/corporate")
public class CorporateLoanController {

    private static final int RECENT_LIMIT = 50;

    private final EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase;
    private final RequestCorporateLoanUseCase requestCorporateLoanUseCase;
    private final DisburseCorporateLoanUseCase disburseCorporateLoanUseCase;
    private final LoadCorporateLoanPort loadCorporateLoanPort;

    public CorporateLoanController(EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase,
                                   RequestCorporateLoanUseCase requestCorporateLoanUseCase,
                                   DisburseCorporateLoanUseCase disburseCorporateLoanUseCase,
                                   LoadCorporateLoanPort loadCorporateLoanPort) {
        this.evaluateCorporateCreditUseCase = evaluateCorporateCreditUseCase;
        this.requestCorporateLoanUseCase = requestCorporateLoanUseCase;
        this.disburseCorporateLoanUseCase = disburseCorporateLoanUseCase;
        this.loadCorporateLoanPort = loadCorporateLoanPort;
    }

    @GetMapping("/credit/{stockCode}")
    public ResponseEntity<CorporateCreditResponse> credit(@PathVariable String stockCode) {
        return ResponseEntity.ok(CorporateCreditResponse.from(
                evaluateCorporateCreditUseCase.evaluate(stockCode)));
    }

    @PostMapping
    public ResponseEntity<CorporateLoanResponse> request(@Valid @RequestBody CorporateLoanRequestBody req,
                                                         Authentication authentication) {
        CorporateLoanResponse body = CorporateLoanResponse.from(requestCorporateLoanUseCase.request(
                new RequestCorporateLoanCommand(req.stockCode(), req.principal(), req.termDays(),
                        callerUserId(authentication))));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{id}/disburse")
    public ResponseEntity<CorporateLoanResponse> disburse(@PathVariable Long id) {
        return ResponseEntity.ok(CorporateLoanResponse.from(disburseCorporateLoanUseCase.disburse(id)));
    }

    /**
     * 기업대출 목록. ADMIN/MANAGER 는 전체(운영 콘솔), 그 외(CEO)는 본인 신청 건만 —
     * 소유권 스코핑은 요청 파라미터가 아니라 JWT 주체에서 파생한다(IDOR 방지 가드레일).
     */
    @GetMapping
    public ResponseEntity<List<CorporateLoanResponse>> list(
            @RequestParam(required = false) String stockCode,
            Authentication authentication) {
        List<CorporateLoan> loans;
        if (isOperator(authentication)) {
            loans = (stockCode == null || stockCode.isBlank()
                    ? loadCorporateLoanPort.findRecent(RECENT_LIMIT)
                    : loadCorporateLoanPort.findByStockCode(stockCode));
        } else {
            loans = loadCorporateLoanPort.findByOwner(callerUserId(authentication), RECENT_LIMIT);
            if (stockCode != null && !stockCode.isBlank()) {
                loans = loans.stream().filter(l -> stockCode.equals(l.getStockCode())).toList();
            }
        }
        return ResponseEntity.ok(loans.stream().map(CorporateLoanResponse::from).toList());
    }

    /** JWT 인증 주체에서 신청자 식별자(userId)를 추출한다. 미인증/식별불가면 403. */
    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    /** 운영 권한(ADMIN/MANAGER) 여부 — 전체 목록 조회 허용 대상. */
    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }
}
