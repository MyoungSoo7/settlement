package github.lms.lemuel.deposit.adapter.in.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 운영자 수기 입금·출금 요청 (SPEC §3.16 {@code /admin/deposits} 콘솔).
 *
 * <p>{@code referenceId} 를 <b>필수</b>로 받는 이유: 원장의 L3 멱등 방어선이
 * {@code UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)} 라,
 * 이 값이 없으면 같은 요청을 두 번 보냈을 때 DB 가 중복을 알아볼 근거가 없다. 수기 경로야말로
 * 재전송·더블클릭이 잦으므로 자동 경로보다 더 엄격해야 한다.
 */
public record DepositEntryRequest(
        @NotNull @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        BigDecimal amount,

        @NotBlank(message = "referenceId 는 멱등 키라 필수입니다")
        String referenceId,

        @NotBlank(message = "referenceType 은 멱등 키라 필수입니다")
        String referenceType) {
}
