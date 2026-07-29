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
 * 따라서 이 애그리거트에는 평가액을 바꾸는 메서드가 없다. 주기적 재평가·마진콜은 Phase 2 이월이며,
 * 도입되더라도 평가액 <em>이력 행 추가</em>로 표현해야지 이 값을 덮어쓰면 안 된다.
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
    private final LocalDateTime appraisedAt;
    private CollateralStatus status;

    private Collateral(Long id, CollateralType type, String description, BigDecimal appraisedValue,
                       LocalDateTime appraisedAt, CollateralStatus status) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.appraisedValue = appraisedValue;
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
        // 금액은 도메인 진입 시 Money(scale 2, HALF_UP)로 정규화한다(money-safety).
        return new Collateral(null, type, description.trim(), Money.of(appraisedValue).toBigDecimal(),
                appraisedAt, CollateralStatus.PLEDGED);
    }

    /** 영속화된 상태를 재구성(리포지토리 전용). */
    public static Collateral reconstitute(Long id, CollateralType type, String description,
                                          BigDecimal appraisedValue, LocalDateTime appraisedAt,
                                          CollateralStatus status) {
        return new Collateral(id, type, description, appraisedValue, appraisedAt, status);
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
     * 유효담보가치 — 한도 산정의 기준액.
     *
     * <p>Phase 1 은 선순위 채권이 없다고 보므로 평가액과 같다. 선순위 차감(Phase 2)이 도입되면
     * {@code 평가액 − 선순위채권액}이 되며, 호출 측(정책)은 이 메서드만 보므로 영향받지 않는다.
     */
    public BigDecimal effectiveValue() {
        return appraisedValue;
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
    public LocalDateTime getAppraisedAt() { return appraisedAt; }
    public CollateralStatus getStatus() { return status; }
}
