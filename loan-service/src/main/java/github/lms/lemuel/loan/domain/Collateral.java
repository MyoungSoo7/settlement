package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 담보 엔티티. 순수 POJO — 프레임워크 의존 0.
 *
 * <p>평가액은 <b>설정 시점의 값을 영구 보존</b>한다(정산의 {@code commission_rate} 스냅샷과 같은 이력
 * 재현성 철학) — 사후에 시세가 변해도 이미 실행된 대출의 심사 근거는 재현 가능해야 하기 때문이다.
 * 따라서 이 애그리거트에는 평가액을 바꾸는 메서드가 없다. 주기적 재평가는 이 값을 덮어쓰지 않고
 * 별도 이력({@code CollateralRevaluation})으로 쌓는다.
 *
 * <p><b>유효담보가치 = 평가액 − 선순위채권액</b>. 앞선 근저당이 있으면 그 몫은 이미 남의 담보력이므로
 * 우리 한도 계산에서 빼야 한다. 초과 선순위는 음수가 아니라 0으로 clamp 한다 — 담보가치가 마이너스가
 * 될 수는 없고, 초과분은 우리 채권과 무관하기 때문이다.
 *
 * <p><b>LTV 는 여기서 계산하지 않는다</b> — 담보는 "얼마짜리인가"(유효담보가치)까지만 알고, "얼마를
 * 빌려줄 수 있는가"는 {@code SecuredLoanPolicy} 의 책임이다. 정책 밖 인라인 계산은 경계값 테스트를
 * 우회시키므로 금지된다.
 */
public class Collateral {

    private final Long id;
    private final CollateralType type;
    private final String description;
    private final BigDecimal appraisedValue;
    private final BigDecimal seniorClaimAmount;
    private final LocalDateTime appraisedAt;
    private CollateralStatus status;

    private Collateral(Long id, CollateralType type, String description, BigDecimal appraisedValue,
                       BigDecimal seniorClaimAmount, LocalDateTime appraisedAt, CollateralStatus status) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.appraisedValue = appraisedValue;
        this.seniorClaimAmount = seniorClaimAmount;
        this.appraisedAt = appraisedAt;
        this.status = status;
    }

    /**
     * 신규 담보 설정. 상태는 PLEDGED 이며 대출 실행 전에 {@link #activate()} 로 유효화되어야 한다.
     *
     * @param type           담보 유형
     * @param description    담보물 표시(부동산 소재지 등)
     * @param appraisedValue 감정평가액(양수) — 설정 시점 스냅샷으로 보존된다
     * @param appraisedAt    평가 시각 — 응용 계층이 KST {@link java.time.Clock} 으로 만들어 넘긴다
     */
    public static Collateral pledge(CollateralType type, String description, BigDecimal appraisedValue,
                                    LocalDateTime appraisedAt) {
        return pledge(type, description, appraisedValue, BigDecimal.ZERO, appraisedAt);
    }

    /**
     * 선순위 채권액을 지정해 담보를 설정한다.
     *
     * @param seniorClaimAmount 선순위 채권액(0 이상) — 유효담보가치에서 차감된다.
     *                          평가액을 넘어도 예외가 아니며 유효담보가치가 0 이 된다(담보력 없음).
     */
    public static Collateral pledge(CollateralType type, String description, BigDecimal appraisedValue,
                                    BigDecimal seniorClaimAmount, LocalDateTime appraisedAt) {
        if (type == null) {
            throw new LoanInvariantViolationException("담보 유형은 필수입니다");
        }
        if (description == null || description.isBlank()) {
            throw new LoanInvariantViolationException("담보물 표시는 필수입니다");
        }
        if (appraisedValue == null || appraisedValue.signum() <= 0) {
            throw new LoanInvariantViolationException("담보 평가액은 양수여야 합니다: " + appraisedValue);
        }
        if (appraisedAt == null) {
            throw new LoanInvariantViolationException("담보 평가 시각은 필수입니다");
        }
        if (seniorClaimAmount == null || seniorClaimAmount.signum() < 0) {
            throw new LoanInvariantViolationException(
                    "선순위 채권액은 0 이상이어야 합니다: " + seniorClaimAmount);
        }
        // 금액은 도메인 진입 시 Money(scale 2, HALF_UP)로 정규화한다(money-safety).
        return new Collateral(null, type, description.trim(), Money.of(appraisedValue).toBigDecimal(),
                Money.of(seniorClaimAmount).toBigDecimal(), appraisedAt, CollateralStatus.PLEDGED);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static Collateral reconstitute(Long id, CollateralType type, String description,
                                          BigDecimal appraisedValue, LocalDateTime appraisedAt,
                                          CollateralStatus status) {
        return reconstitute(id, type, description, appraisedValue, BigDecimal.ZERO, appraisedAt, status);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static Collateral reconstitute(Long id, CollateralType type, String description,
                                          BigDecimal appraisedValue, BigDecimal seniorClaimAmount,
                                          LocalDateTime appraisedAt, CollateralStatus status) {
        return new Collateral(id, type, description, appraisedValue, seniorClaimAmount, appraisedAt, status);
    }

    /** 담보 설정 완료 — 대출 실행의 전제 조건. */
    public void activate() {
        requireTransition(CollateralStatus.ACTIVE);
        this.status = CollateralStatus.ACTIVE;
    }

    /** 말소 — 완제 후 해지 또는 심사 거절로 인한 설정 해제. */
    public void release() {
        requireTransition(CollateralStatus.RELEASED);
        this.status = CollateralStatus.RELEASED;
    }

    /**
     * 유효담보가치 — 한도 산정의 기준액. {@code max(0, 평가액 − 선순위채권액)}.
     *
     * <p>호출 측(정책)은 이 메서드만 보므로, 선순위 개념이 추가돼도 LTV 산정 코드는 수정되지 않는다.
     */
    public BigDecimal effectiveValue() {
        Money remaining = Money.of(appraisedValue).minus(Money.of(seniorClaimAmount).min(Money.of(appraisedValue)));
        return remaining.toBigDecimal();
    }

    /** 담보력이 있는 상태인지 — 대출 실행 가드가 참조한다. */
    public boolean isActive() {
        return status == CollateralStatus.ACTIVE;
    }

    // 상태 전이 가드 — 허용 전이는 CollateralStatus#canTransitionTo 단일 출처에 위임한다.
    private void requireTransition(CollateralStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidLoanStateException(status, target);
        }
    }

    public Long getId() { return id; }
    public CollateralType getType() { return type; }
    public String getDescription() { return description; }
    public BigDecimal getAppraisedValue() { return appraisedValue; }
    public BigDecimal getSeniorClaimAmount() { return seniorClaimAmount; }
    public LocalDateTime getAppraisedAt() { return appraisedAt; }
    public CollateralStatus getStatus() { return status; }
}
