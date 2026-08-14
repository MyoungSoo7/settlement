package github.lms.lemuel.deposit.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 운영자 수기 입금 요청.
 *
 * <p>{@code referenceId} 를 <b>필수</b>로 받는 것이 이 DTO 의 핵심이다 — 이 값이
 * {@code uq_deposit_entries_natural}(account_id, entry_type, reference_type, reference_id,
 * offset_sequence) 의 구성요소이자 유일한 멱등 키다. 서버가 UUID 를 생성해 주면 재전송이 곧
 * 이중 입금이 되므로, 멱등 키는 반드시 호출자가 정한다.
 *
 * <p>{@code referenceType} 은 선택이며 기본값 {@code MANUAL} — 수기 보정임을 원장에 남긴다.
 */
public record CreditDepositRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String referenceId,
        String referenceType
) {
    private static final String DEFAULT_REFERENCE_TYPE = "MANUAL";

    /** 미지정 시 MANUAL — 자동 경로(SETTLEMENT)와 수기 보정을 원장에서 구분하기 위함. */
    public String referenceTypeOrDefault() {
        return (referenceType == null || referenceType.isBlank()) ? DEFAULT_REFERENCE_TYPE : referenceType;
    }
}
