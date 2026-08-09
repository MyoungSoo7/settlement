package github.lms.lemuel.account.banking.savings.adapter.in.web;

import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.InstallmentSavingsResponse;
import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.OpenInstallmentSavingsRequest;
import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.PayInstallmentRequest;
import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase.CloseInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase.OpenInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase.PayInstallmentCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.QueryInstallmentSavingsUseCase;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
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
 * 적금 REST 표면 — 개설·회차 납입·해지(만기/중도)·조회.
 *
 * <p><b>IDOR 방지</b>: 예금주 식별자는 요청(경로·쿼리·본문) 어디에도 없다. 오직 JWT 주체
 * ({@link AuthPrincipal#userId()})에서만 파생하며, userId 가 없는 구(舊) 토큰은 403 으로 거절한다.
 * 계약 id 는 경로에서 오므로 "남의 savingsId + 내 토큰" 조합은 언제나 만들어질 수 있고, 그 판정은
 * 도메인의 {@code assertOwnedBy} 가 한다(403).
 *
 * <p>{@code ownerId} 규약: 계정계 {@code OwnerType.DEPOSITOR} 의 ownerId 는 userId 숫자 문자열이다 —
 * 여기서 {@code String.valueOf(userId)} 로 바꾸는 지점이 그 규약의 유일한 진입점이다.
 *
 * <p><b>날짜도 요청에서 받지 않는다</b>: 개설일·납입일·해지일은 전부 응용 서비스의 서버 시계(Clock)가
 * 정한다. 이 표면의 어떤 record 에도 날짜 필드가 없어야 소급 납입으로 이자를 만들어내는 경로가 닫힌다.
 */
@RestController
@RequestMapping("/api/banking/savings")
public class InstallmentSavingsController {

    private final OpenInstallmentSavingsUseCase openInstallmentSavingsUseCase;
    private final PayInstallmentUseCase payInstallmentUseCase;
    private final CloseInstallmentSavingsUseCase closeInstallmentSavingsUseCase;
    private final QueryInstallmentSavingsUseCase queryInstallmentSavingsUseCase;

    public InstallmentSavingsController(OpenInstallmentSavingsUseCase openInstallmentSavingsUseCase,
                                        PayInstallmentUseCase payInstallmentUseCase,
                                        CloseInstallmentSavingsUseCase closeInstallmentSavingsUseCase,
                                        QueryInstallmentSavingsUseCase queryInstallmentSavingsUseCase) {
        this.openInstallmentSavingsUseCase = openInstallmentSavingsUseCase;
        this.payInstallmentUseCase = payInstallmentUseCase;
        this.closeInstallmentSavingsUseCase = closeInstallmentSavingsUseCase;
        this.queryInstallmentSavingsUseCase = queryInstallmentSavingsUseCase;
    }

    /** 적금 개설. */
    @PostMapping
    public ResponseEntity<InstallmentSavingsResponse> open(@RequestBody OpenInstallmentSavingsRequest request,
                                                           Authentication authentication) {
        return ResponseEntity.ok(InstallmentSavingsResponse.from(
                openInstallmentSavingsUseCase.open(new OpenInstallmentSavingsCommand(
                        callerDepositorId(authentication),
                        request.productName(),
                        request.savingsType(),
                        request.monthlyAmount(),
                        request.paymentLimit(),
                        request.annualRate(),
                        request.earlyTerminationRate(),
                        request.termMonths()))));
    }

    /** 회차 납입 — GL 에 {@code savingsInstallmentPaid} 전표가 함께 기표된다. */
    @PostMapping("/{savingsId}/installments")
    public ResponseEntity<InstallmentSavingsResponse> pay(@PathVariable Long savingsId,
                                                          @RequestBody PayInstallmentRequest request,
                                                          Authentication authentication) {
        return ResponseEntity.ok(InstallmentSavingsResponse.from(
                payInstallmentUseCase.pay(new PayInstallmentCommand(
                        savingsId,
                        callerDepositorId(authentication),
                        request.round(),
                        request.amount()))));
    }

    /**
     * 만기 해지 — 약정이율 적용. <b>본문을 받지 않는다</b>: 해지일은 서버 시계가 정하고,
     * 만기/중도 구분은 본문 플래그가 아니라 엔드포인트로 가른다.
     */
    @PostMapping("/{savingsId}/close/maturity")
    public ResponseEntity<InstallmentSavingsResponse> closeOnMaturity(@PathVariable Long savingsId,
                                                                      Authentication authentication) {
        return ResponseEntity.ok(InstallmentSavingsResponse.from(
                closeInstallmentSavingsUseCase.closeOnMaturity(new CloseInstallmentSavingsCommand(
                        savingsId, callerDepositorId(authentication)))));
    }

    /** 중도 해지 — 중도해지이율 적용. 마찬가지로 본문 없음. */
    @PostMapping("/{savingsId}/close/early")
    public ResponseEntity<InstallmentSavingsResponse> closeEarly(@PathVariable Long savingsId,
                                                                 Authentication authentication) {
        return ResponseEntity.ok(InstallmentSavingsResponse.from(
                closeInstallmentSavingsUseCase.closeEarly(new CloseInstallmentSavingsCommand(
                        savingsId, callerDepositorId(authentication)))));
    }

    /** 단건 조회 — 남의 계약이면 403. */
    @GetMapping("/{savingsId}")
    public ResponseEntity<InstallmentSavingsResponse> get(@PathVariable Long savingsId,
                                                          Authentication authentication) {
        return ResponseEntity.ok(InstallmentSavingsResponse.from(
                queryInstallmentSavingsUseCase.get(savingsId, callerDepositorId(authentication))));
    }

    /**
     * 내 적금 목록. 경로에 사용자 식별자가 <b>없다</b>는 것이 이 엔드포인트의 전부다 —
     * 대상을 경로로 받는 순간 그대로 IDOR 경로가 된다.
     */
    @GetMapping
    public ResponseEntity<List<InstallmentSavingsResponse>> listMine(Authentication authentication) {
        List<InstallmentSavingsResponse> body =
                queryInstallmentSavingsUseCase.listMine(callerDepositorId(authentication)).stream()
                        .map(InstallmentSavingsResponse::from)
                        .toList();
        return ResponseEntity.ok(body);
    }

    /** JWT 인증 주체에서 예금주 id(=userId 숫자 문자열)를 추출한다. 미인증/식별불가면 403. */
    private static String callerDepositorId(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return String.valueOf(principal.userId());
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }
}
