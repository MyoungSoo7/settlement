package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.EarlyTerminationResponse;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.LeaseApplyRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.LeaseContractResponse;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.LeaseScheduleResponse;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase.ApplyLeaseCommand;
import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.exception.LeaseContractNotFoundException;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * 리스·할부 물건금융 API — 신청·조회는 차주, 승인·개시·수납·해지는 운영자(ADMIN/MANAGER).
 *
 * <p><b>IDOR 가드레일</b>: 차주 식별자를 요청 바디·경로에서 받지 않고 <b>JWT 주체에서 파생</b>한다.
 * 남의 계약 조회는 403 이 아니라 <b>404</b> 다 — 403 은 "그 번호의 계약이 있다"를 알려 주므로,
 * 번호를 훑어 존재를 확인하는 경로가 열린다.
 *
 * <p><b>회차 수납이 운영자 조작인 이유</b>: 실제 입금 확인(자동이체 결과·수납 대사)을 거친 뒤 반영되는
 * 행위라 차주가 스스로 "냈다"고 선언할 수 있으면 안 된다.
 */
@RestController
@RequestMapping("/loans/leases")
public class LeaseController {

    private static final int RECENT_LIMIT = 50;

    private final ManageLeaseContractUseCase manageLeaseContractUseCase;
    private final LoadLeaseContractPort loadLeaseContractPort;

    public LeaseController(ManageLeaseContractUseCase manageLeaseContractUseCase,
                           LoadLeaseContractPort loadLeaseContractPort) {
        this.manageLeaseContractUseCase = manageLeaseContractUseCase;
        this.loadLeaseContractPort = loadLeaseContractPort;
    }

    // ─── 차주 ─────────────────────────────────────────────────────────────────

    /** 리스·할부 신청 — 차주 식별자는 JWT 에서 파생한다. */
    @PostMapping
    public ResponseEntity<LeaseContractResponse> apply(@Valid @RequestBody LeaseApplyRequest request,
                                                       Authentication authentication) {
        AuthPrincipal principal = requirePrincipal(authentication);
        LeaseContract contract = manageLeaseContractUseCase.apply(new ApplyLeaseCommand(
                principal.userId(), displayName(principal), null,
                request.financeType(), request.assetDescription(), request.acquisitionCost(),
                request.downPaymentOrZero(), request.depositOrZero(), request.residualValueOrZero(),
                request.termMonths(), request.annualRatePercent()));
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaseContractResponse.from(contract));
    }

    /** 내 계약 목록(최신순). */
    @GetMapping
    public List<LeaseContractResponse> myContracts(Authentication authentication) {
        Long userId = requirePrincipal(authentication).userId();
        return loadLeaseContractPort.findByBorrower(userId, RECENT_LIMIT).stream()
                .map(LeaseContractResponse::from)
                .toList();
    }

    /** 계약 상세 — 차주는 본인 것만, 운영자는 전체. */
    @GetMapping("/{contractId}")
    public LeaseContractResponse detail(@PathVariable Long contractId, Authentication authentication) {
        return LeaseContractResponse.from(loadOwned(contractId, authentication));
    }

    /** 회차표 — 만기 잔액이 잔존가치로 수렴한다. */
    @GetMapping("/{contractId}/schedule")
    public LeaseScheduleResponse schedule(@PathVariable Long contractId, Authentication authentication) {
        return LeaseScheduleResponse.from(loadOwned(contractId, authentication).getSchedule());
    }

    /** 중도해지 정산액 조회 — 상태를 바꾸지 않는다(고객 안내용). */
    @GetMapping("/{contractId}/early-termination")
    public EarlyTerminationResponse quoteEarlyTermination(@PathVariable Long contractId,
                                                          @RequestParam BigDecimal penaltyRatePercent,
                                                          Authentication authentication) {
        Long scopedUserId = isOperator(authentication) ? null : requirePrincipal(authentication).userId();
        return EarlyTerminationResponse.from(manageLeaseContractUseCase
                .quoteEarlyTermination(contractId, penaltyRatePercent, scopedUserId));
    }

    // ─── 운영자 ────────────────────────────────────────────────────────────────

    @PostMapping("/{contractId}/approve")
    public LeaseContractResponse approve(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.approve(contractId));
    }

    @PostMapping("/{contractId}/reject")
    public LeaseContractResponse reject(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.reject(contractId));
    }

    @PostMapping("/{contractId}/cancel")
    public LeaseContractResponse cancel(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.cancel(contractId));
    }

    /** 물건 인도 완료 → 계약 개시. 리스는 돈이 아니라 물건이 나가야 시작된다. */
    @PostMapping("/{contractId}/activate")
    public LeaseContractResponse activate(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.activate(contractId));
    }

    /** 회차 수납 반영 — 입금 확인 후 운영자가 처리한다. */
    @PostMapping("/{contractId}/installments")
    public LeaseContractResponse payInstallment(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.payInstallment(contractId));
    }

    @PostMapping("/{contractId}/overdue")
    public LeaseContractResponse markOverdue(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.markOverdue(contractId));
    }

    /** 기한이익상실 — 연체를 거쳐야만 가능하다(도메인 상태표가 강제). */
    @PostMapping("/{contractId}/default")
    public LeaseContractResponse markDefaulted(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.markDefaulted(contractId));
    }

    @PostMapping("/{contractId}/mature")
    public LeaseContractResponse mature(@PathVariable Long contractId, Authentication authentication) {
        requireOperator(authentication);
        return LeaseContractResponse.from(manageLeaseContractUseCase.mature(contractId));
    }

    /** 중도해지 확정 — 규정손해금을 산정하고 계약을 종결한다. */
    @PostMapping("/{contractId}/early-termination")
    public EarlyTerminationResponse terminateEarly(@PathVariable Long contractId,
                                                   @RequestParam BigDecimal penaltyRatePercent,
                                                   Authentication authentication) {
        requireOperator(authentication);
        return EarlyTerminationResponse.from(
                manageLeaseContractUseCase.terminateEarly(contractId, penaltyRatePercent));
    }

    // ─── 인가 ─────────────────────────────────────────────────────────────────

    private LeaseContract loadOwned(Long contractId, Authentication authentication) {
        LeaseContract contract = loadLeaseContractPort.findById(contractId)
                .orElseThrow(() -> new LeaseContractNotFoundException(
                        "리스·할부 계약을 찾을 수 없습니다: " + contractId));
        if (isOperator(authentication)) {
            return contract;
        }
        Long userId = requirePrincipal(authentication).userId();
        if (!userId.equals(contract.getBorrower().userId())) {
            // 남의 계약은 존재를 알리지 않는다(403 대신 404).
            throw new LeaseContractNotFoundException("리스·할부 계약을 찾을 수 없습니다: " + contractId);
        }
        return contract;
    }

    private static AuthPrincipal requirePrincipal(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && principal.userId() != null) {
            return principal;
        }
        throw new AccessDeniedException("인증 주체에서 사용자 식별자를 확인할 수 없습니다.");
    }

    private static String displayName(AuthPrincipal principal) {
        return principal.email() == null || principal.email().isBlank()
                ? "사용자 " + principal.userId()
                : principal.email();
    }

    private static void requireOperator(Authentication authentication) {
        if (!isOperator(authentication)) {
            throw new AccessDeniedException("리스 계약 운영 조작은 관리자만 가능합니다.");
        }
    }

    private static boolean isOperator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }
}
