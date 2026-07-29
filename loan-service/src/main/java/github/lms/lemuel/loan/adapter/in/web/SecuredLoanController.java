package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.dto.SecuredLoanRequestBodies.MortgageRequestBody;
import github.lms.lemuel.loan.adapter.in.web.dto.SecuredLoanRequestBodies.PersonalCreditRequestBody;
import github.lms.lemuel.loan.adapter.in.web.dto.SecuredLoanRequestBodies.SecuredLoanRepayRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.SecuredLoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.ManageSecuredLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.in.RepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.MortgageCommand;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase.PersonalCreditCommand;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 담보/개인신용 대출 API — 신청·조회는 차주(개인/법인), 승인·실행·연체 관리는 운영자(ADMIN/MANAGER).
 *
 * <p><b>IDOR 가드레일</b>: 차주 식별자를 요청 바디·경로에서 받지 않고 <b>JWT 주체에서 파생</b>한다.
 * 조회·상환은 소유권을 대조해 불일치면 403, 대출이 없으면 404다. 운영자는 소유권 대조를 우회하되
 * 그 판정 역시 JWT 권한에서만 나온다.
 */
@RestController
@RequestMapping("/loans/secured")
public class SecuredLoanController {

    private static final int RECENT_LIMIT = 50;

    private final RequestSecuredLoanUseCase requestSecuredLoanUseCase;
    private final DisburseSecuredLoanUseCase disburseSecuredLoanUseCase;
    private final RepaySecuredLoanUseCase repaySecuredLoanUseCase;
    private final ManageSecuredLoanCollectionUseCase manageSecuredLoanCollectionUseCase;
    private final LoadSecuredLoanPort loadSecuredLoanPort;

    public SecuredLoanController(RequestSecuredLoanUseCase requestSecuredLoanUseCase,
                                 DisburseSecuredLoanUseCase disburseSecuredLoanUseCase,
                                 RepaySecuredLoanUseCase repaySecuredLoanUseCase,
                                 ManageSecuredLoanCollectionUseCase manageSecuredLoanCollectionUseCase,
                                 LoadSecuredLoanPort loadSecuredLoanPort) {
        this.requestSecuredLoanUseCase = requestSecuredLoanUseCase;
        this.disburseSecuredLoanUseCase = disburseSecuredLoanUseCase;
        this.repaySecuredLoanUseCase = repaySecuredLoanUseCase;
        this.manageSecuredLoanCollectionUseCase = manageSecuredLoanCollectionUseCase;
        this.loadSecuredLoanPort = loadSecuredLoanPort;
    }

    // ─── 신청(차주) ───────────────────────────────────────────────────────────

    /** 주택담보대출 신청 — 담보 설정 + LTV 한도 심사까지 수행한다. 한도 초과면 422. */
    @PostMapping("/mortgage")
    public ResponseEntity<SecuredLoanResponse> requestMortgage(@Valid @RequestBody MortgageRequestBody body,
                                                               Authentication authentication) {
        SecuredLoan loan = requestSecuredLoanUseCase.requestMortgage(new MortgageCommand(
                callerUserId(authentication), body.borrowerName(), body.registrationNo(),
                body.collateralDescription(), body.declaredCollateralValue(),
                body.principal(), body.termMonths(), body.repaymentMethod()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SecuredLoanResponse.from(loan));
    }

    /** 개인신용대출 신청 — CB 점수 심사. 등급 미달·한도 초과면 422. */
    @PostMapping("/personal")
    public ResponseEntity<SecuredLoanResponse> requestPersonalCredit(
            @Valid @RequestBody PersonalCreditRequestBody body, Authentication authentication) {
        SecuredLoan loan = requestSecuredLoanUseCase.requestPersonalCredit(new PersonalCreditCommand(
                callerUserId(authentication), body.borrowerName(), body.registrationNo(),
                body.principal(), body.termMonths(), body.repaymentMethod(), body.cbScore()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SecuredLoanResponse.from(loan));
    }

    // ─── 조회 ────────────────────────────────────────────────────────────────

    /** 목록 — 차주는 본인 것만 본다. 운영자도 같은 스코프 조회를 쓰되 대상 차주를 지정할 수 없다. */
    @GetMapping
    public ResponseEntity<List<SecuredLoanResponse>> myLoans(Authentication authentication) {
        List<SecuredLoan> loans =
                loadSecuredLoanPort.findByBorrower(callerUserId(authentication), RECENT_LIMIT);
        return ResponseEntity.ok(loans.stream().map(SecuredLoanResponse::from).toList());
    }

    /** 상세 — 본인 또는 운영자만. 없으면 404, 소유권 불일치면 403. */
    @GetMapping("/{loanId}")
    public ResponseEntity<SecuredLoanResponse> detail(@PathVariable Long loanId,
                                                      Authentication authentication) {
        return ResponseEntity.ok(SecuredLoanResponse.from(requireOwnerOrOperator(loanId, authentication)));
    }

    // ─── 심사·실행(운영자) ────────────────────────────────────────────────────

    @PostMapping("/{loanId}/approve")
    public ResponseEntity<SecuredLoanResponse> approve(@PathVariable Long loanId,
                                                       Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(SecuredLoanResponse.from(disburseSecuredLoanUseCase.approve(loanId)));
    }

    @PostMapping("/{loanId}/reject")
    public ResponseEntity<SecuredLoanResponse> reject(@PathVariable Long loanId,
                                                      Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(SecuredLoanResponse.from(disburseSecuredLoanUseCase.reject(loanId)));
    }

    @PostMapping("/{loanId}/disburse")
    public ResponseEntity<SecuredLoanResponse> disburse(@PathVariable Long loanId,
                                                        Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(SecuredLoanResponse.from(disburseSecuredLoanUseCase.disburse(loanId)));
    }

    // ─── 연체 관리(운영자) ────────────────────────────────────────────────────

    @PostMapping("/{loanId}/overdue")
    public ResponseEntity<SecuredLoanResponse> markOverdue(@PathVariable Long loanId,
                                                           Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(
                SecuredLoanResponse.from(manageSecuredLoanCollectionUseCase.markOverdue(loanId)));
    }

    /** 기한이익상실 — 연체를 거친 대출에만 가능(도메인 상태머신이 직행을 막는다). */
    @PostMapping("/{loanId}/accelerate")
    public ResponseEntity<SecuredLoanResponse> accelerate(@PathVariable Long loanId,
                                                          Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(
                SecuredLoanResponse.from(manageSecuredLoanCollectionUseCase.accelerate(loanId)));
    }

    // ─── 상환(차주 본인) ──────────────────────────────────────────────────────

    /** 회차 상환 — 원금·이자 분리 납입. 소유권은 서비스에서도 재검증한다(이중 방어). */
    @PostMapping("/{loanId}/repay")
    public ResponseEntity<SecuredLoanResponse> repay(@PathVariable Long loanId,
                                                     @Valid @RequestBody SecuredLoanRepayRequest body,
                                                     Authentication authentication) {
        SecuredLoan loan = repaySecuredLoanUseCase.repay(loanId, callerUserId(authentication),
                body.principalPortion(), body.interestPortion());
        return ResponseEntity.ok(SecuredLoanResponse.from(loan));
    }

    // ─── 인가 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 조회 소유권 대조 — 본인 소유이거나 운영자여야 통과. 식별자는 요청이 아니라 JWT 주체에서 파생한다.
     * 대출 없으면 404, 소유권 불일치면 403.
     */
    private SecuredLoan requireOwnerOrOperator(Long loanId, Authentication authentication) {
        Long caller = callerUserId(authentication);
        SecuredLoan loan = loadSecuredLoanPort.findById(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
        if (isOperator(authentication) || caller.equals(loan.getBorrower().userId())) {
            return loan;
        }
        throw new AccessDeniedException("본인 소유가 아닌 대출입니다. loanId=" + loanId);
    }

    private static void requireOperator(Authentication authentication) {
        callerUserId(authentication);   // 미인증/식별불가 → 403
        if (!isOperator(authentication)) {
            throw new AccessDeniedException("운영 권한이 필요한 작업입니다.");
        }
    }

    /** JWT 인증 주체에서 사용자 식별자를 추출한다. 미인증/식별불가면 403. */
    private static Long callerUserId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }
}
