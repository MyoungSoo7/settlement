package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.out.persistence.LoanManualIdempotencyGuard;
import github.lms.lemuel.loan.application.port.in.EnforceCollateralUseCase;
import github.lms.lemuel.loan.application.port.in.RevalueCollateralUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 담보 재평가·실행 운영자 콘솔.
 *
 * <p><b>왜 이 컨트롤러가 생겼나</b>: 담보 재평가(마진콜 140%·청산 120% 판정)와 실행(처분·대위변제)은
 * 서비스와 정책 상수와 단위 테스트가 모두 있었는데 <b>어떤 어댑터도 호출하지 않아</b> 런타임에서
 * 도달할 수 없었다. 담보 가치가 반토막 나도 아무 일도 일어나지 않는 상태였다(역산 PRD §10-C).
 * 재발은 {@code LoanArchitectureTest#모든_인바운드_포트는_어댑터에서_호출된다} 가 구조로 막는다.
 *
 * <p><b>왜 배치가 아니라 REST 인가</b>: 재평가 값은 시스템이 스스로 알 수 없다 — 부동산은 감정가,
 * 금융자산은 시세 스냅샷이 외부에서 들어온다. 그래서 진입점은 "값을 들고 오는 쪽"이 부르는 API 이고,
 * 자동 수집(시세 이벤트 구독 등)은 이 위에 얹으면 된다. 트리거가 아예 없는 상태부터 먼저 없앤다.
 *
 * <p><b>인가</b>: 담보 처분·대위변제는 채권 회수 조작이라 운영자(ADMIN/MANAGER) 전용이다.
 * 판정은 요청 파라미터가 아니라 JWT 권한에서만 나온다({@code SecuredLoanController} 와 동형).
 *
 * <p><b>멱등</b>: 처분·대위변제는 전표와 상각을 남기는 1회성 조작이다. {@code Idempotency-Key} 를
 * 주면 같은 키의 재제출을 선점으로 막는다(키가 없으면 미적용 — 하위호환).
 */
@Tag(name = "Collateral", description = "담보 재평가·실행 (운영자)")
@RestController
@RequestMapping("/loans/secured/{loanId}/collateral")
public class CollateralController {

    private final RevalueCollateralUseCase revalueCollateralUseCase;
    private final EnforceCollateralUseCase enforceCollateralUseCase;
    private final LoanManualIdempotencyGuard idempotencyGuard;

    public CollateralController(RevalueCollateralUseCase revalueCollateralUseCase,
                                EnforceCollateralUseCase enforceCollateralUseCase,
                                LoanManualIdempotencyGuard idempotencyGuard) {
        this.revalueCollateralUseCase = revalueCollateralUseCase;
        this.enforceCollateralUseCase = enforceCollateralUseCase;
        this.idempotencyGuard = idempotencyGuard;
    }

    @Operation(summary = "담보 재평가·마진콜 판정",
            description = "새 평가액으로 담보유지비율을 재판정한다. 140% 미달이면 MARGIN_CALL(추가담보 요구액 포함), "
                    + "120% 미달이면 LIQUIDATION 으로 이관된다. 재평가는 조회가 아니라 판정을 동반하는 조작이다.")
    @PostMapping("/revalue")
    public ResponseEntity<RevalueCollateralUseCase.RevaluationResult> revalue(
            @PathVariable Long loanId,
            @Valid @RequestBody RevalueRequest request,
            Authentication authentication) {
        requireOperator(authentication);
        return ResponseEntity.ok(
                revalueCollateralUseCase.revalue(loanId, request.revaluedValue(), request.source()));
    }

    @Operation(summary = "담보 처분",
            description = "매각대금으로 채권을 회수하고 부족분은 상각한다. 기한이익상실 이후 경로.")
    @PostMapping("/dispose")
    public ResponseEntity<EnforceCollateralUseCase.EnforcementResult> dispose(
            @PathVariable Long loanId,
            @Valid @RequestBody DisposeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long caller = callerUserId(authentication);
        requireOperator(authentication);
        if (isDuplicate(idempotencyKey, "collateral-dispose", caller)) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.ok(enforceCollateralUseCase.dispose(loanId, request.proceeds()));
    }

    @Operation(summary = "보증기관 대위변제 청구",
            description = "보증부 담보의 회수 경로. 회수액은 보증비율(85%)만큼이고 미보증분은 상각된다 "
                    + "— 보증부라도 손실이 0 이 아니다.")
    @PostMapping("/subrogate")
    public ResponseEntity<EnforceCollateralUseCase.EnforcementResult> subrogate(
            @PathVariable Long loanId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        Long caller = callerUserId(authentication);
        requireOperator(authentication);
        if (isDuplicate(idempotencyKey, "collateral-subrogate", caller)) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.ok(enforceCollateralUseCase.subrogate(loanId));
    }

    /** 재평가 요청 — 평가액과 출처(MARKET_SERVICE / COMMON_DATA_SERVICE / MANUAL). */
    public record RevalueRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal revaluedValue,
            String source) {
    }

    /** 처분 요청 — 매각대금(양수). */
    public record DisposeRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal proceeds) {
    }

    // ─── 인가·멱등 헬퍼 (SecuredLoanController 와 동형) ────────────────────────

    private boolean isDuplicate(String idempotencyKey, String endpoint, Long callerUserId) {
        return idempotencyKey != null && !idempotencyKey.isBlank()
                && !idempotencyGuard.claim(idempotencyKey, endpoint, String.valueOf(callerUserId));
    }

    private static void requireOperator(Authentication authentication) {
        callerUserId(authentication);   // 미인증/식별불가 → 403
        if (!isOperator(authentication)) {
            throw new AccessDeniedException("운영 권한이 필요한 작업입니다.");
        }
    }

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
