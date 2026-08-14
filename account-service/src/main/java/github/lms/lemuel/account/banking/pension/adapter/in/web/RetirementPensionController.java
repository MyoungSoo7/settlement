package github.lms.lemuel.account.banking.pension.adapter.in.web;

import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.ChangeInvestmentInstructionRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.ContributeRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.MidWithdrawalRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.OpenPensionRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.PayBenefitRequest;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.RetirementPensionResponse;
import github.lms.lemuel.account.banking.pension.adapter.in.web.dto.StartBenefitRequest;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionQuery;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ChangeInvestmentInstructionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ContributeCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.OpenPensionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.PayBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.SettleInterestCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.StartBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.WithdrawMidwayCommand;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 퇴직연금 셀프서비스 API — 가입·부담금·운용지시·수급·중도인출·조회.
 *
 * <p><b>IDOR 방지</b>: 가입자 식별자는 경로·본문·쿼리 어디서도 받지 않고 JWT 주체
 * ({@link AuthPrincipal#userId()})에서만 파생한다. 요청 DTO 에 {@code subscriberId} 류 필드가
 * 아예 없으므로 실려 와도 바인딩될 자리가 없다. userId 가 없는 구(舊) 토큰은 403 이다.
 *
 * <p>타인 계약에 대한 조작은 서비스가 소유자 대조 후 {@code ACCESS_DENIED}(403) 로 닫는다 —
 * 경로의 {@code pensionId} 는 "무엇을" 만 지정하고 "누가" 는 언제나 토큰이 정한다.
 *
 * <p><b>날짜·이자금액·나이를 받지 않는다</b>: 발생일은 서버 {@code Clock}, 이자는 계약이율·경과일수,
 * 만 나이·가입기간은 가입 시 기록한 생년월일·개설일에서 각각 서버가 산출한다. 요청 DTO 에 그 필드들이
 * 아예 없으므로 실려 와도 바인딩될 자리가 없다.
 *
 * <p><b>운영자 전용 두 경로</b>: 이자 확정(/interest-settlements)과 급여 지급(/benefit-payments)은
 * SecurityConfig 에서 ADMIN/MANAGER 로 잠긴다 — 돈을 만들거나 내보내는 동작이라 가입자 셀프서비스가
 * 아니다. 그래서 이 둘만 토큰 주체로 소유권을 대조하지 않으며(대조하면 운영자가 통과할 수 없다),
 * 대신 전표의 owner 를 계약에 적힌 가입자에서 뽑는다. 나머지 경로는 전부 토큰 주체 = 소유자다.
 */
@RestController
@RequestMapping("/api/banking/pensions")
public class RetirementPensionController {

    private final RetirementPensionUseCase retirementPensionUseCase;
    private final RetirementPensionQuery retirementPensionQuery;

    public RetirementPensionController(RetirementPensionUseCase retirementPensionUseCase,
                                       RetirementPensionQuery retirementPensionQuery) {
        this.retirementPensionUseCase = retirementPensionUseCase;
        this.retirementPensionQuery = retirementPensionQuery;
    }

    @PostMapping
    public ResponseEntity<RetirementPensionResponse> open(@RequestBody OpenPensionRequest request,
                                                          Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(RetirementPensionResponse.from(
                retirementPensionUseCase.open(new OpenPensionCommand(
                        subscriberId(authentication), request.scheme(), request.employerName(),
                        request.birthDate(), request.annualRate(),
                        request.productName(), request.productRate()))));
    }

    @PostMapping("/{pensionId}/contributions")
    public ResponseEntity<RetirementPensionResponse> contribute(@PathVariable Long pensionId,
                                                                @RequestBody ContributeRequest request,
                                                                Authentication authentication) {
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.contribute(new ContributeCommand(
                        subscriberId(authentication), pensionId, request.amount(), request.source()))));
    }

    /**
     * 운용수익 확정 — <b>요청 본문이 없다</b>. 이자 금액을 받으면 인증된 가입자가 자기 적립금을 임의로
     * 부풀리고 그것이 그대로 {@code DR 이자비용 / CR 수신부채} 로 기표된다(무제한 발권).
     * 금액도 발생일도 서버가 정한다. 라우트는 ADMIN/MANAGER 전용이다.
     */
    @PostMapping("/{pensionId}/interest-settlements")
    public ResponseEntity<RetirementPensionResponse> settleInterest(@PathVariable Long pensionId,
                                                                    Authentication authentication) {
        requireAuthenticated(authentication);
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.settleInterest(new SettleInterestCommand(pensionId))));
    }

    @PutMapping("/{pensionId}/investment-instruction")
    public ResponseEntity<RetirementPensionResponse> changeInvestmentInstruction(
            @PathVariable Long pensionId,
            @RequestBody ChangeInvestmentInstructionRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.changeInvestmentInstruction(new ChangeInvestmentInstructionCommand(
                        subscriberId(authentication), pensionId, request.productName(), request.rate()))));
    }

    @PostMapping("/{pensionId}/benefit")
    public ResponseEntity<RetirementPensionResponse> startBenefit(@PathVariable Long pensionId,
                                                                  @RequestBody StartBenefitRequest request,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.startBenefit(new StartBenefitCommand(
                        subscriberId(authentication), pensionId, request.benefitType()))));
    }

    /**
     * 퇴직급여 지급 — 수급자 식별자를 받지 않는다. 지급 대상은 언제나 계약에 적힌 가입자이며,
     * 라우트는 ADMIN/MANAGER 전용이다.
     */
    @PostMapping("/{pensionId}/benefit-payments")
    public ResponseEntity<RetirementPensionResponse> payBenefit(@PathVariable Long pensionId,
                                                                @RequestBody PayBenefitRequest request,
                                                                Authentication authentication) {
        requireAuthenticated(authentication);
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.payBenefit(new PayBenefitCommand(pensionId, request.amount()))));
    }

    @PostMapping("/{pensionId}/mid-withdrawals")
    public ResponseEntity<RetirementPensionResponse> withdrawMidway(@PathVariable Long pensionId,
                                                                    @RequestBody MidWithdrawalRequest request,
                                                                    Authentication authentication) {
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionUseCase.withdrawMidway(new WithdrawMidwayCommand(
                        subscriberId(authentication), pensionId, request.amount(), request.reason()))));
    }

    @GetMapping("/{pensionId}")
    public ResponseEntity<RetirementPensionResponse> get(@PathVariable Long pensionId,
                                                         Authentication authentication) {
        return ResponseEntity.ok(RetirementPensionResponse.from(
                retirementPensionQuery.get(subscriberId(authentication), pensionId)));
    }

    /** 내 퇴직연금 목록 — 경로에 가입자 식별자가 <b>없다</b>는 것이 이 엔드포인트의 전부다. */
    @GetMapping
    public ResponseEntity<List<RetirementPensionResponse>> listMine(Authentication authentication) {
        List<RetirementPensionResponse> body = retirementPensionQuery.listMine(subscriberId(authentication))
                .stream().map(RetirementPensionResponse::from).toList();
        return ResponseEntity.ok(body);
    }

    /**
     * JWT 인증 주체에서 가입자 식별자를 뽑는다 — GL owner_id 규약(DEPOSITOR = userId 숫자 문자열)에 맞춰
     * 문자열로 변환한다. 미인증·식별 불가 토큰은 403.
     */
    private static String subscriberId(Authentication authentication) {
        requireAuthenticated(authentication);
        return String.valueOf(((AuthPrincipal) authentication.getPrincipal()).userId());
    }

    /**
     * 운영자 경로용 최소 관문 — 소유권은 보지 않지만 <b>인증 주체는 반드시 있어야 한다</b>.
     * 라우트 권한(ADMIN/MANAGER)은 SecurityConfig 가 잠그지만, 그 설정이 언젠가 느슨해지더라도
     * 익명 호출로 남의 적립금에 이자를 붙이거나 급여를 내보낼 수는 없어야 한다(다층 방어).
     */
    private static void requireAuthenticated(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || principal.userId() == null) {
            throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
        }
    }
}
