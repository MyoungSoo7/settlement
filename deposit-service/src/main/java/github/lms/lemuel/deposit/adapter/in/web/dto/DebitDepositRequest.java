package github.lms.lemuel.deposit.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 운영자 수기 출금 요청.
 *
 * <p>멱등 키 규약은 {@link CreditDepositRequest} 와 동일하다 — {@code referenceId} 는 호출자가 정한다.
 * 기본 {@code referenceType} 만 다르다(정상 경로가 PAYOUT 이므로 수기 보정은 MANUAL 로 구분).
 */
public record DebitDepositRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String referenceId,
        String referenceType
) {
    private static final String DEFAULT_REFERENCE_TYPE = "MANUAL";

    public String referenceTypeOrDefault() {
        return (referenceType == null || referenceType.isBlank()) ? DEFAULT_REFERENCE_TYPE : referenceType;
    }
}
