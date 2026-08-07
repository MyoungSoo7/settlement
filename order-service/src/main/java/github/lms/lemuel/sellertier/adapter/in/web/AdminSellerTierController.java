package github.lms.lemuel.sellertier.adapter.in.web;

import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase;
import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase.TierEvaluationReport;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 셀러 등급 운영 콘솔 (ADR 0031).
 *
 * <pre>
 *   POST /admin/seller-tiers/evaluate                 미리보기(무변경)
 *   POST /admin/seller-tiers/evaluate?dryRun=false    실제 재산정
 *   GET  /admin/seller-tiers/policy                   적용 중인 임계 확인
 * </pre>
 *
 * <p>등급 하나가 수수료율·정산주기·홀드백을 동시에 바꾸므로 <b>미리보기가 기본값</b>이다 —
 * 파라미터를 빠뜨린 호출이 곧바로 전 셀러 등급을 바꾸면 안 된다.
 *
 * <p>{@code /policy} 는 현재 적용 중인 임계를 그대로 보여준다. 임계는 설정값이라 배포 환경마다
 * 다를 수 있고, "지금 무슨 기준으로 도는가"를 확인할 방법이 없으면 미리보기 결과를 해석할 수 없다.
 *
 * <p>권한은 SecurityConfig 의 {@code /admin/seller-tiers/**} 매처(ADMIN)로 제한된다 —
 * 등급은 정산 금액을 바꾸므로 조회 콘솔과 달리 MANAGER 에게 열지 않는다.
 */
@RestController
@RequestMapping("/admin/seller-tiers")
public class AdminSellerTierController {

    private static final int DEFAULT_LIMIT = 1000;

    private final EvaluateSellerTiersUseCase useCase;
    private final SellerTierPolicy policy;

    public AdminSellerTierController(EvaluateSellerTiersUseCase useCase, SellerTierPolicy policy) {
        this.useCase = useCase;
        this.policy = policy;
    }

    @Operation(summary = "등급 재산정 — dryRun 기본 true",
            description = "실제 반영은 dryRun=false 를 명시해야 한다. 행별로 이전·이후 등급과 근거 거래액을 낸다.")
    @PostMapping("/evaluate")
    public ResponseEntity<TierEvaluationReport> evaluate(
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(
                useCase.evaluate(date == null ? LocalDate.now() : date, dryRun, limit));
    }

    @Operation(summary = "적용 중인 등급 임계 확인 — 미리보기 결과를 해석하려면 기준을 알아야 한다")
    @GetMapping("/policy")
    public ResponseEntity<PolicyView> policy() {
        return ResponseEntity.ok(new PolicyView(policy.vipThreshold(), policy.strategicThreshold()));
    }

    public record PolicyView(BigDecimal vipThreshold, BigDecimal strategicThreshold) { }
}
