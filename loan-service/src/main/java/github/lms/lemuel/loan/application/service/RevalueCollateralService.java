package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.RevalueCollateralUseCase;
import github.lms.lemuel.loan.application.port.out.CollateralRiskPort;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralRevaluation;
import github.lms.lemuel.loan.domain.MarginCall;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanPolicy;
import github.lms.lemuel.loan.domain.exception.SecuredLoanNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 담보 재평가 · 마진콜 판정.
 *
 * <p><b>재평가와 판정을 한 트랜잭션에 묶는다</b> — 시가를 새로 안 시점이 곧 조치를 결정해야 하는
 * 시점이다. 기록만 하고 판정을 미루면 그 사이 담보 부족이 방치된다.
 *
 * <p><b>설정 시점 평가액은 건드리지 않는다.</b> 재평가는 이력 행으로만 쌓이고, 판정은 최신 이력을
 * 기준으로 한다. 덮어쓰면 그 대출의 한도 산정 근거가 사후에 바뀌어 재현이 불가능해진다.
 *
 * <p><b>대출 상태는 이 서비스가 바꾸지 않는다.</b> 청산선 미달이면 마진콜을 ESCALATED 로 이관 표시만
 * 하고, 연체·기한이익상실 전이는 {@code SecuredLoanCollectionService} 의 몫이다 — 마진콜 판정이
 * 대출 상태머신을 직접 건드리면 두 생명주기가 결합되고, 배치가 실수로 대출을 부도 처리하는 사고 경로가 생긴다.
 */
@Service
public class RevalueCollateralService implements RevalueCollateralUseCase {

    private final LoadSecuredLoanPort loadSecuredLoanPort;
    private final CollateralRiskPort collateralRiskPort;
    private final BigDecimal baseRatePercent;
    private final BigDecimal realEstateLtvRatio;
    private final Clock clock;

    public RevalueCollateralService(LoadSecuredLoanPort loadSecuredLoanPort,
                                    CollateralRiskPort collateralRiskPort,
                                    @Value("${app.loan.secured.base-rate-percent:3.5}")
                                    BigDecimal baseRatePercent,
                                    @Value("${app.loan.secured.real-estate-ltv:0.70}")
                                    BigDecimal realEstateLtvRatio,
                                    Clock clock) {
        this.loadSecuredLoanPort = loadSecuredLoanPort;
        this.collateralRiskPort = collateralRiskPort;
        this.baseRatePercent = baseRatePercent;
        this.realEstateLtvRatio = realEstateLtvRatio;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RevaluationResult revalue(Long loanId, BigDecimal revaluedValue, String source) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 동시 판정을 직렬화한다 — 같은 부족 상황으로 마진콜이 두 번 열리지 않게.
        SecuredLoan loan = loadSecuredLoanPort.findByIdForUpdate(loanId)
                .orElseThrow(() -> new SecuredLoanNotFoundException(
                        "담보대출을 찾을 수 없습니다. loanId=" + loanId));
        Collateral collateral = loan.getCollateral();
        if (collateral == null) {
            throw new IllegalArgumentException(
                    "담보 없는 대출은 재평가 대상이 아닙니다. loanId=" + loanId);
        }

        collateralRiskPort.appendRevaluation(
                CollateralRevaluation.of(collateral.getId(), revaluedValue, source, now));

        // 마진콜은 시가가 움직이는 금융자산 계열만 해당한다 — 주택담보는 시세가 떨어져도
        // 추가담보를 요구하지 않는 상품이라 이력만 남기고 조치는 없다.
        if (!collateral.getType().supportsMarginCall()) {
            return sufficient(loan, collateral, revaluedValue, BigDecimal.ZERO);
        }

        SecuredLoanPolicy policy = new SecuredLoanPolicy(baseRatePercent, realEstateLtvRatio);
        BigDecimal effective = effectiveValueOf(revaluedValue, collateral.getSeniorClaimAmount());
        BigDecimal outstanding = loan.getOutstanding();
        BigDecimal coverage = policy.coverageRatio(effective, outstanding);
        Optional<MarginCall> open = collateralRiskPort.findOpenMarginCall(loan.getId());

        if (policy.requiresLiquidation(effective, outstanding)) {
            // 청산선 미달 — 마진콜을 열어(없으면) 즉시 이관 표시한다. 이관 사실을 남기지 않고
            // 대출만 부도 처리하면 "왜 처분했는지"의 근거가 사라진다.
            MarginCall call = open.orElseGet(() -> collateralRiskPort.saveMarginCall(MarginCall.open(
                    loan.getId(), collateral.getId(),
                    policy.marginCallShortfall(effective, outstanding), now)));
            call.escalate(now);
            collateralRiskPort.saveMarginCall(call);
            return new RevaluationResult(loan.getId(), collateral.getId(), revaluedValue, coverage,
                    Outcome.LIQUIDATION, call.getRequiredAmount());
        }

        if (policy.requiresMarginCall(effective, outstanding)) {
            BigDecimal shortfall = policy.marginCallShortfall(effective, outstanding);
            // 이미 활성 마진콜이 있으면 새로 열지 않는다 — 요구액은 최초 발생 시점 값을 보존한다.
            MarginCall call = open.orElseGet(() -> collateralRiskPort.saveMarginCall(
                    MarginCall.open(loan.getId(), collateral.getId(), shortfall, now)));
            return new RevaluationResult(loan.getId(), collateral.getId(), revaluedValue, coverage,
                    Outcome.MARGIN_CALL, call.getRequiredAmount());
        }

        // 유지비율 회복 — 열려 있던 마진콜을 해소한다.
        open.ifPresent(call -> {
            call.resolve(now);
            collateralRiskPort.saveMarginCall(call);
        });
        return new RevaluationResult(loan.getId(), collateral.getId(), revaluedValue, coverage,
                Outcome.SUFFICIENT, BigDecimal.ZERO);
    }

    private RevaluationResult sufficient(SecuredLoan loan, Collateral collateral,
                                         BigDecimal revaluedValue, BigDecimal required) {
        SecuredLoanPolicy policy = new SecuredLoanPolicy(baseRatePercent, realEstateLtvRatio);
        BigDecimal coverage = policy.coverageRatio(
                effectiveValueOf(revaluedValue, collateral.getSeniorClaimAmount()), loan.getOutstanding());
        return new RevaluationResult(loan.getId(), collateral.getId(), revaluedValue, coverage,
                Outcome.SUFFICIENT, required);
    }

    /**
     * 재평가액 기준 유효담보가치 = max(0, 재평가액 − 선순위). {@code Collateral#effectiveValue()} 와
     * 같은 규칙이지만 대상이 <b>최신 시가</b>라 여기서 다시 계산한다 — 담보 엔티티의 값은 설정 시점
     * 스냅샷이므로 그 메서드를 쓸 수 없다.
     */
    private static BigDecimal effectiveValueOf(BigDecimal currentValue, BigDecimal seniorClaimAmount) {
        BigDecimal senior = seniorClaimAmount == null ? BigDecimal.ZERO : seniorClaimAmount;
        BigDecimal effective = currentValue.subtract(senior);
        return effective.signum() <= 0 ? BigDecimal.ZERO : effective;
    }
}
