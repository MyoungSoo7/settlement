package github.lms.lemuel.deposit.adapter.in.web.dto;

import github.lms.lemuel.deposit.domain.DepositHolderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 예치금 상계(Offset) 요청 (SPEC §3.16 {@code /admin/deposits} 콘솔).
 *
 * <p>선점된 hold 에서 먼저 차감하고, 모자라면 available 에서 마저 끌어오며, 그래도 부족하면
 * 부족분을 shortfall 레코드로 남긴다. 즉 <b>부족은 실패가 아니라 기록되는 정상 결과</b>이므로
 * 이 API 는 잔고가 모자라도 200 을 돌려준다.
 *
 * <p>{@code offsetSequence} 는 한 hold 에 대한 분할 상계의 회차다. L3 멱등 키
 * {@code UNIQUE(account_id, entry_type, reference_type, reference_id, offset_sequence)} 의
 * 마지막 칸이라, 분할 상계 2회차를 1회차와 같은 번호로 보내면 DB 가 중복으로 보고 막는다.
 */
public record ApplyOffsetRequest(
        @NotNull(message = "holderType 은 필수입니다")
        DepositHolderType holderType,

        @NotBlank(message = "holderReference 는 멱등 키라 필수입니다")
        String holderReference,

        @NotNull @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        BigDecimal offsetAmount,

        @Min(value = 0, message = "offsetSequence 는 0 이상입니다")
        int offsetSequence,

        OffsetDateTime occurredAt) {
}
