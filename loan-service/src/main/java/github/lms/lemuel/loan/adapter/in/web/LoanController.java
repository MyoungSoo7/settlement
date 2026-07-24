package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.dto.LoanRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.LoanResponse;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.ManageLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
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
    private final ManageLoanCollectionUseCase manageLoanCollectionUseCase;
    private final LoadLoanPort loadLoanPort;

    public LoanController(RequestLoanUseCase requestLoanUseCase,
                          DisburseLoanUseCase disburseLoanUseCase,
                          ManageLoanCollectionUseCase manageLoanCollectionUseCase,
                          LoadLoanPort loadLoanPort) {
        this.requestLoanUseCase = requestLoanUseCase;
        this.disburseLoanUseCase = disburseLoanUseCase;
        this.manageLoanCollectionUseCase = manageLoanCollectionUseCase;
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
    public ResponseEntity<LoanResponse> disburse(@PathVariable Long id, Authentication authentication) {
        // 집행(실자금 지급)은 소유권 대조를 강제한다: 대출의 셀러가 인증 주체 본인이어야 실행할 수 있다.
        // 식별자를 요청 경로가 아니라 JWT 주체(userId)에서 파생해 타인 대출 강제 실행을 차단한다(IDOR 가드레일).
        Long callerSellerId = callerSellerId(authentication);   // 미인증/식별불가 → 403 (항상 non-null)
        LoanAdvance loan = loadLoanPort.load(id);               // 없는 대출 → IllegalArgumentException → 400
        // non-null 인 callerSellerId 를 좌변에 둔다 — 저장된 sellerId 가 null(손상/레거시)이어도 NPE→500 대신
        // 소유권 불일치(403)로 안전하게 처리(CorporateLoanController 소유권 대조와 동형).
        if (!callerSellerId.equals(loan.getSellerId())) {
            throw new AccessDeniedException("본인 소유가 아닌 대출입니다. loanId=" + id);
        }
        return ResponseEntity.ok(LoanResponse.from(disburseLoanUseCase.disburse(id)));
    }

    /**
     * 연체 진입(회수 담당자 조작) — 실행된 대출을 OVERDUE 로 전이한다. 회수 리스크 관제 운영 액션이라
     * ADMIN 만 허용한다. 상태 전이 불변식(잔액 있는 DISBURSED 만 가능)은 도메인이 강제한다.
     */
    @PostMapping("/{id}/overdue")
    public ResponseEntity<LoanResponse> markOverdue(@PathVariable Long id, Authentication authentication) {
        requireAdmin(authentication);
        return ResponseEntity.ok(LoanResponse.from(manageLoanCollectionUseCase.markOverdue(id)));
    }

    /**
     * 상각(대손 확정) — 연체 대출을 WRITTEN_OFF 로 전이하고 미상환잔액을 대손 전표로 인식한다.
     * 손실 확정 액션이라 ADMIN 만 허용한다.
     */
    @PostMapping("/{id}/write-off")
    public ResponseEntity<LoanResponse> writeOff(@PathVariable Long id, Authentication authentication) {
        requireAdmin(authentication);
        return ResponseEntity.ok(LoanResponse.from(manageLoanCollectionUseCase.writeOff(id)));
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

    /** 회수(연체·상각) 운영 액션은 ADMIN 전용 — 인증 주체가 ROLE_ADMIN 이 아니면 403. */
    private static void requireAdmin(Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("회수(연체·상각) 조작은 관리자만 가능합니다.");
        }
    }
}
