package github.lms.lemuel.insurance.adapter.in.web;

import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.CancelPolicyUseCase.CancelPolicyCommand;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase;
import github.lms.lemuel.insurance.application.port.in.GetPolicyPayoutsUseCase.GeneralPayoutSummary;
import github.lms.lemuel.insurance.application.port.in.PolicyTerminationResult;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase;
import github.lms.lemuel.insurance.application.port.in.SurrenderPolicyUseCase.SurrenderPolicyCommand;
import github.lms.lemuel.insurance.domain.exception.InvalidPolicyTransitionException;
import github.lms.lemuel.insurance.domain.exception.PolicyNotFoundException;
import github.lms.lemuel.insurance.domain.exception.PolicyOwnershipException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 계약 해지·철회 + 일반지급 내역 API (§14) — Policy.surrender/cancel 의 REST 진입점.
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * ⚠️ §13 과 동일 한계: fcId 는 JWT 주체가 아니라 요청 입력 — 담당 FC 대조는 실수 방지 수준.
 */
@RestController
@RequestMapping("/api/insurance/policies")
public class PolicyController {

    private final SurrenderPolicyUseCase surrenderUseCase;
    private final CancelPolicyUseCase cancelUseCase;
    private final GetPolicyPayoutsUseCase getPayoutsUseCase;

    public PolicyController(SurrenderPolicyUseCase surrenderUseCase,
                            CancelPolicyUseCase cancelUseCase,
                            GetPolicyPayoutsUseCase getPayoutsUseCase) {
        this.surrenderUseCase = surrenderUseCase;
        this.cancelUseCase = cancelUseCase;
        this.getPayoutsUseCase = getPayoutsUseCase;
    }

    /** 임의해지 — 전이 + 해약환급금 산출·payout 생성이 한 tx. 409/403/404. */
    @PostMapping("/{policyNumber}/surrender")
    public PolicyTerminationResult surrender(@PathVariable String policyNumber,
                                             @Valid @RequestBody TerminateRequest request) {
        return surrenderUseCase.surrender(new SurrenderPolicyCommand(policyNumber, request.fcId()));
    }

    /** 청약철회 — 15일 창구(도메인 강제) + 기납입 전액 payout. 409/403/404. */
    @PostMapping("/{policyNumber}/cancel")
    public PolicyTerminationResult cancel(@PathVariable String policyNumber,
                                          @Valid @RequestBody TerminateRequest request) {
        return cancelUseCase.cancel(new CancelPolicyCommand(policyNumber, request.fcId()));
    }

    /** 일반지급 내역 — 산출근거 스냅샷(D-G5) 포함. */
    @GetMapping("/{policyNumber}/payouts")
    public List<GeneralPayoutSummary> payouts(@PathVariable String policyNumber) {
        return getPayoutsUseCase.byPolicyNumber(policyNumber);
    }

    /** @param fcId 담당 FC 대조용 — §13 과 동일 한계(요청 입력, 실수 방지 수준) */
    public record TerminateRequest(@NotBlank String fcId) {
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(PolicyNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PolicyOwnershipException.class)
    public ResponseEntity<Map<String, String>> forbidden(PolicyOwnershipException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidPolicyTransitionException.class)
    public ResponseEntity<Map<String, String>> conflict(InvalidPolicyTransitionException e) {
        // 상태머신 비허용 전이(이미 종료·창구 초과 등) — 409
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }
}
