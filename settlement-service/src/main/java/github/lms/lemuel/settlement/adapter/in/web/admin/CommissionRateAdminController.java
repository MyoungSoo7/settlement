package github.lms.lemuel.settlement.adapter.in.web.admin;

import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase;
import github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase;
import github.lms.lemuel.settlement.application.port.in.SimulateCommissionRateUseCase.RateSimulation;
import github.lms.lemuel.settlement.application.port.out.ListCommissionRatePoliciesPort;
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
import java.time.OffsetDateTime;
import java.util.List;

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
    private final ListCommissionRatePoliciesPort listPort;

    public CommissionRateAdminController(RegisterCommissionRatePolicyUseCase registerUseCase,
                                         SaveCommissionRatePolicyPort savePort,
                                         SimulateCommissionRateUseCase simulateUseCase,
                                         ListCommissionRatePoliciesPort listPort) {
        this.registerUseCase = registerUseCase;
        this.savePort = savePort;
        this.simulateUseCase = simulateUseCase;
        this.listPort = listPort;
    }

    // @Valid 없이는 아래 @NotNull 이 장식으로만 남아, 요율이 빠진 요청이 그대로 통과한다
    // (DB NOT NULL 이 500 으로 잡아 400 계약이 깨진다).
    @Operation(summary = "요율 정책 등록",
            description = "이미 정산이 생성된 구간으로 소급 등록하면 400 으로 거부된다(ADR 0032 결정 ⑤).")
    @PostMapping
    public ResponseEntity<CommissionRatePolicy> register(@jakarta.validation.Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(registerUseCase.register(request.toCommand(), LocalDate.now()));
    }

    @Operation(summary = "요율 정책 목록",
            description = "조기 종료에 필요한 id 와 감사 근거(reason·createdBy)를 함께 준다. "
                    + "기본은 살아 있는 정책만 — includeClosed=true 로 종료 이력까지 본다.")
    @GetMapping
    public ResponseEntity<List<PolicyView>> list(
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(listPort.findRows(includeClosed).stream().map(PolicyView::of).toList());
    }

    /**
     * 목록 응답 뷰 — 포트 행에 {@code closed} 를 <b>필드로</b> 펼친다.
     *
     * <p>레코드의 파생 메서드는 직렬화되지 않아, 계산값을 그대로 두면 화면이 매번
     * {@code closedAt != null} 을 다시 판정해야 한다. 판정 기준은 서버에 한 벌만 둔다.
     */
    public record PolicyView(Long id, RateScope scope, String scopeKey, BigDecimal rate,
                             LocalDate effectiveFrom, LocalDate effectiveTo,
                             String reason, String createdBy,
                             OffsetDateTime createdAt, OffsetDateTime closedAt, boolean closed) {
        static PolicyView of(ListCommissionRatePoliciesPort.PolicyRow r) {
            return new PolicyView(r.id(), r.scope(), r.scopeKey(), r.rate(),
                    r.effectiveFrom(), r.effectiveTo(), r.reason(), r.createdBy(),
                    r.createdAt(), r.closedAt(), r.closed());
        }
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
