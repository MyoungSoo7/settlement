package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.dto.LoanRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.LoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
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
 * 선정산 대출 신청/실행/조회 API.
 *
 * <p>셀러 소유권은 요청 파라미터가 아니라 JWT 인증 주체(userId)에서 파생·대조한다(IDOR 방지 가드레일):
 * 신청은 인증 주체를 신청 셀러로 삼고(바디에 sellerId 없음), 목록 조회는 본인 것만 허용한다(불일치 403).
 */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final RequestLoanUseCase requestLoanUseCase;
    private final DisburseLoanUseCase disburseLoanUseCase;
    private final LoadLoanPort loadLoanPort;

    public LoanController(RequestLoanUseCase requestLoanUseCase,
                          DisburseLoanUseCase disburseLoanUseCase,
                          LoadLoanPort loadLoanPort) {
        this.requestLoanUseCase = requestLoanUseCase;
        this.disburseLoanUseCase = disburseLoanUseCase;
        this.loadLoanPort = loadLoanPort;
    }

    @PostMapping
    public ResponseEntity<LoanResponse> request(@Valid @RequestBody LoanRequest req,
                                                Authentication authentication) {
        // 신청 셀러는 요청 바디가 아니라 인증 주체에서 파생한다(타인 명의 신청 차단).
        LoanResponse body = LoanResponse.from(requestLoanUseCase.request(
                new RequestLoanCommand(callerSellerId(authentication), req.principal(), req.financingDays())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/{id}/disburse")
    public ResponseEntity<LoanResponse> disburse(@PathVariable Long id) {
        return ResponseEntity.ok(LoanResponse.from(disburseLoanUseCase.disburse(id)));
    }

    @GetMapping
    public ResponseEntity<List<LoanResponse>> bySeller(@RequestParam Long sellerId,
                                                       Authentication authentication) {
        requireSelf(sellerId, authentication);
        return ResponseEntity.ok(loadLoanPort.findBySeller(sellerId).stream()
                .map(LoanResponse::from)
                .toList());
    }

    /** JWT 인증 주체에서 셀러 식별자(userId)를 추출한다. 미인증/식별불가면 403. */
    private static Long callerSellerId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 셀러 식별자를 확인할 수 없습니다.");
    }

    /** 요청한 셀러 리소스가 인증 주체 본인의 것인지 확인한다(타인 리소스 접근 → 403). */
    private static void requireSelf(Long requestedSellerId, Authentication authentication) {
        if (!callerSellerId(authentication).equals(requestedSellerId)) {
            throw new AccessDeniedException("본인 소유가 아닌 셀러 리소스입니다. sellerId=" + requestedSellerId);
        }
    }
}
