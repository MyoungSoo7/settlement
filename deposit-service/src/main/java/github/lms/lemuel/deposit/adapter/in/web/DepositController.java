package github.lms.lemuel.deposit.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.deposit.adapter.in.web.dto.DepositAccountResponse;
import github.lms.lemuel.deposit.application.port.in.QueryDepositAccountUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셀러 예치금 조회 표면 (SPEC §3.16). 게이트웨이가 {@code /api/deposits/**} 를 이 서비스(8112)로 보낸다.
 *
 * <p>셀프서비스 경로에 셀러 식별자를 <b>받지 않는</b> 것이 이 컨트롤러의 핵심이다 —
 * 대상을 경로로 받는 순간 {@code /accounts/{sellerId}} 는 그대로 IDOR 경로가 된다. 그래서
 * 본인 조회는 {@code /accounts/me} 로 JWT 주체에서만 sellerId 를 파생하고, 남의 계좌를 보는
 * {@code /accounts/{sellerId}} 는 SecurityConfig 에서 ADMIN·MANAGER 로 잠근다
 * ({@code SellerBankAccountSelfController} 와 같은 방식).
 */
@RestController
@RequestMapping("/api/deposits")
public class DepositController {

    private final QueryDepositAccountUseCase queryDepositAccountUseCase;

    public DepositController(QueryDepositAccountUseCase queryDepositAccountUseCase) {
        this.queryDepositAccountUseCase = queryDepositAccountUseCase;
    }

    /**
     * 내 예치 계좌 잔고. 계좌가 아직 없으면 404 다 — 0원 계좌를 지어내 200 으로 돌려주면
     * "계좌가 열린 적 없다"와 "잔고가 0이다"를 화면에서 구분할 수 없게 된다.
     */
    @GetMapping("/accounts/me")
    public ResponseEntity<DepositAccountResponse> myAccount(Authentication authentication) {
        return queryDepositAccountUseCase.findBySellerId(callerSellerId(authentication))
                .map(DepositAccountResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 특정 셀러의 예치 계좌 (ADMIN·MANAGER 전용 — SecurityConfig 에서 강제). */
    @GetMapping("/accounts/{sellerId}")
    public ResponseEntity<DepositAccountResponse> accountOf(@PathVariable Long sellerId) {
        return queryDepositAccountUseCase.findBySellerId(sellerId)
                .map(DepositAccountResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * JWT 인증 주체에서 sellerId 를 추출한다. 미인증·식별불가면 403.
     *
     * <p>셀러 식별자는 사용자 식별자와 같은 값을 쓴다 — {@code SellerBankAccountSelfController},
     * {@code TaxInvoiceSellerController} 의 셀프서비스 경로와 동일한 파생 규칙이다.
     */
    private static Long callerSellerId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal.userId();
        }
        throw new AccessDeniedException("인증 주체에서 셀러 식별자를 확인할 수 없습니다.");
    }
}
