package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidProposalException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 산출에 적용된 요율 — 요율 행 식별자 + 값의 스냅샷 쌍.
 *
 * <p>D-P2: 설계는 "어떤 요율 행으로 얼마가 나왔는지"를 함께 보존한다. 요율이 개정돼도
 * 과거 설계의 근거가 재현된다 (settlement {@code commission_rate} 영구보존과 동일 철학).
 */
public record AppliedRate(long rateTableId, BigDecimal ratePerMille) {

    public AppliedRate {
        Objects.requireNonNull(ratePerMille, "ratePerMille");
        if (rateTableId <= 0) {
            throw new InvalidProposalException("요율 행 식별자가 유효하지 않습니다: " + rateTableId);
        }
        if (ratePerMille.signum() <= 0) {
            throw new InvalidProposalException(
                    "요율은 0 보다 커야 합니다: " + ratePerMille.toPlainString());
        }
    }
}
