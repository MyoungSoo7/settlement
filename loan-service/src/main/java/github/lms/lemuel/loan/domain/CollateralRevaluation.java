package github.lms.lemuel.loan.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 담보 재평가 이력(불변 값).
 *
 * <p><b>{@link Collateral#getAppraisedValue()} 를 덮어쓰지 않는 이유</b>: 설정 시점 평가액은 그 대출의
 * 한도 산정 근거다. 시가가 변할 때마다 덮어쓰면 "왜 그때 3억을 승인했나"를 사후에 재현할 수 없다.
 * 그래서 재평가는 이력 행으로 쌓고, 마진콜 판정만 <b>최신 이력</b>을 본다.
 *
 * <p>{@code source} 는 평가 출처(예: {@code MARKET_SERVICE}, {@code COMMON_DATA_SERVICE},
 * {@code MANUAL})다. 어느 경로로 들어온 값인지 남겨야 시세 분쟁 시 추적이 된다.
 *
 * @param collateralId  대상 담보
 * @param revaluedValue 재평가액(양수)
 * @param source        평가 출처
 * @param revaluedAt    평가 시각
 */
public record CollateralRevaluation(Long collateralId, BigDecimal revaluedValue,
                                    String source, LocalDateTime revaluedAt) {

    public CollateralRevaluation {
        if (collateralId == null) {
            throw new LoanInvariantViolationException("재평가 대상 담보 식별자는 필수입니다");
        }
        if (revaluedValue == null || revaluedValue.signum() <= 0) {
            throw new LoanInvariantViolationException("재평가액은 양수여야 합니다: " + revaluedValue);
        }
        if (source == null || source.isBlank()) {
            throw new LoanInvariantViolationException("재평가 출처는 필수입니다");
        }
        if (revaluedAt == null) {
            throw new LoanInvariantViolationException("재평가 시각은 필수입니다");
        }
        source = source.trim();
        revaluedValue = Money.of(revaluedValue).toBigDecimal();
    }

    public static CollateralRevaluation of(Long collateralId, BigDecimal revaluedValue,
                                           String source, LocalDateTime revaluedAt) {
        return new CollateralRevaluation(collateralId, revaluedValue, source, revaluedAt);
    }
}
