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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 계약 해지·철회 + 일반지급 내역 API (§14) — Policy.surrender/cancel 의 REST 진입점.
 *
 * <p>인증: shared-common SecurityConfig 기본 규칙(anyRequest → authenticated) — JWT 필수.
 * <b>FC 식별자는 요청이 아니라 JWT 주체에서만 파생한다</b>({@link FcIdentity}) — 세 경로 모두
 * 담당 FC 본인만 접근하고, 요청 DTO 에 fcId 필드를 두지 않아 본문 바인딩 통로 자체가 없다.
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
    public PolicyTerminationResult surrender(@PathVariable String policyNumber) {
        return surrenderUseCase.surrender(new SurrenderPolicyCommand(policyNumber, requireFcId()));
    }

    /** 청약철회 — 15일 창구(도메인 강제) + 기납입 전액 payout. 409/403/404. */
    @PostMapping("/{policyNumber}/cancel")
    public PolicyTerminationResult cancel(@PathVariable String policyNumber) {
        return cancelUseCase.cancel(new CancelPolicyCommand(policyNumber, requireFcId()));
    }

    /** 일반지급 내역 — 산출근거 스냅샷(D-G5) 포함. 본인 담당 계약만(타인 403). */
    @GetMapping("/{policyNumber}/payouts")
    public List<GeneralPayoutSummary> payouts(@PathVariable String policyNumber) {
        return getPayoutsUseCase.byPolicyNumber(policyNumber, requireFcId());
    }

    /**
     * 현재 요청자의 FC 식별자 — 요청 본문이 아니라 JWT 주체에서만 파생한다(IDOR 차단).
     *
     * <p>해지·철회는 계약을 종료시키고 환급금을 발생시키는 돈 경로다 — 본문 fcId 를 신뢰하면
     * 남의 fcId 를 아는 것만으로 타인 계약을 해지시킬 수 있다. {@link FcIdentity} 단일
     * 초크포인트를 쓴다(가입설계 경로와 동일 관례).
     *
     * @throws PolicyOwnershipException userId 가 없는 구(舊) 토큰 — 403 으로 매핑된다
     *                                  (계약 존재 여부가 새 나가지 않도록 소유권 실패와 동일 응답)
     */
    private static String requireFcId() {
        String fcId = FcIdentity.currentFcId();
        if (fcId == null) {
            throw new PolicyOwnershipException("(인증 주체 없음)", "-");
        }
        return fcId;
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
