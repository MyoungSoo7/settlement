package github.lms.lemuel.deposit.adapter.in.web.dto;

import github.lms.lemuel.deposit.domain.DepositHolderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 예치금 선점(Hold) 요청 (SPEC §3.16 {@code /admin/deposits} 콘솔).
 *
 * <p>{@code (holderType, holderReference)} 가 hold 의 자연키이자 멱등 키다 — 같은 쌍으로 다시
 * 요청하면 새 hold 를 만들지 않고 기존 hold 를 그대로 돌려준다.
 *
 * <p>{@code expiresAt} 은 생략 가능하다(미지정 시 도메인 기본 72시간). 만료를 요청자가 정하게 열어
 * 두되, 안 정해도 무기한 선점이 되지는 않게 한다 — 무기한 hold 는 셀러 잔고를 조용히 잠그는 사고다.
 */
public record PlaceHoldRequest(
        @NotNull(message = "holderType 은 필수입니다")
        DepositHolderType holderType,

        @NotBlank(message = "holderReference 는 멱등 키라 필수입니다")
        String holderReference,

        @NotNull @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        BigDecimal amount,

        LocalDateTime expiresAt) {
}
