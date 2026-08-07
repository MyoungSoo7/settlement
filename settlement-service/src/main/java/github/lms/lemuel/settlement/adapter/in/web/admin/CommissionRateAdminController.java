package github.lms.lemuel.settlement.adapter.in.web.admin;

import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase;
import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase.RateSimulation;
import github.lms.lemuel.settlement.application.port.out.SaveCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.RateScope;
import github.lms.lemuel.settlement.domain.SellerTier;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수수료율 정책 운영 콘솔 (ADR 0032).
 *
 * <pre>
 *   POST /admin/commission-rates                  신규 등록 (reason 필수)
 *   POST /admin/commission-rates/{id}/close       조기 종료
 *   GET  /admin/commission-rates/simulate         해석 결과 미리보기
 * </pre>
 *
 * <p>요율 변경은 행 UPDATE 가 아니라 <b>close + 신규 등록</b>이다 — 이력이 곧 이 테이블이라
 * 과거 값을 덮으면 "그때 왜 그 요율이었나"를 설명할 수 없게 된다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/commission-rates/**} 매처(ADMIN)로 제한된다 —
 * 요율은 정산 금액을 직접 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다.
 */
@RestController
@RequestMapping("/admin/commission-rates")
public class CommissionRateAdminController {

    private final RegisterCommissionRatePolicyUseCase registerUseCase;
    private final SaveCommissionRatePolicyPort savePort;
    private final SimulateCommissionRateUseCase simulateUseCase;

    public CommissionRateAdminController(RegisterCommissionRatePolicyUseCase registerUseCase,
                                         SaveCommissionRatePolicyPort savePort,
                                         SimulateCommissionRateUseCase simulateUseCase) {
        this.registerUseCase = registerUseCase;
        this.savePort = savePort;
        this.simulateUseCase = simulateUseCase;
    }

    @Operation(summary = "요율 정책 등록",
            description = "이미 정산이 생성된 구간으로 소급 등록하면 400 으로 거부된다(ADR 0032 결정 ⑤).")
    @PostMapping
    public ResponseEntity<CommissionRatePolicy> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(registerUseCase.register(request.toCommand(), LocalDate.now()));
    }

    @Operation(summary = "요율 정책 조기 종료 — 요율 변경은 close + 신규 등록으로 한다")
    @PostMapping("/{id}/close")
    public ResponseEntity<Void> close(@PathVariable Long id) {
        savePort.close(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "요율 해석 미리보기 — 이 셀러에게 이 날짜에 어떤 요율이 왜 적용되는가")
    @GetMapping("/simulate")
    public ResponseEntity<RateSimulation> simulate(
            @RequestParam(name = "sellerId", required = false) Long sellerId,
            @RequestParam(name = "tier", required = false) String tier,
            @RequestParam(name = "at", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        return ResponseEntity.ok(simulateUseCase.simulate(
                sellerId, tier == null ? null : SellerTier.fromStringOrDefault(tier), at));
    }

    /** @param reason 왜 이 요율인가 — 감사 없이 요율이 바뀌지 않게 필수 */
    public record RegisterRequest(@NotNull RateScope scope, @NotNull String scopeKey,
                                  @NotNull BigDecimal rate,
                                  @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                                  @NotNull String reason, String createdBy) {
        RegisterPolicyCommand toCommand() {
            return new RegisterPolicyCommand(scope, scopeKey, rate, effectiveFrom, effectiveTo,
                    reason, createdBy == null ? "admin" : createdBy);
        }
    }
}
