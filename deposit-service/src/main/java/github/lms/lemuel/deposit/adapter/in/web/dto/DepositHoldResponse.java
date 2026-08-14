package github.lms.lemuel.deposit.adapter.in.web.dto;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHoldStatus;
import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 예치금 선점(Hold) 응답. 멱등 재요청이면 기존 hold 가 그대로 돌아온다. */
public record DepositHoldResponse(
        Long id,
        Long accountId,
        DepositHolderType holderType,
        String holderReference,
        BigDecimal originalAmount,
        BigDecimal remainingAmount,
        DepositHoldStatus status,
        LocalDateTime expiresAt) {

    public static DepositHoldResponse from(DepositHold hold) {
        return new DepositHoldResponse(
                hold.getId(),
                hold.getAccountId(),
                hold.getHolderType(),
                hold.getHolderReference(),
                hold.getOriginalAmount(),
                hold.getRemainingAmount(),
                hold.getStatus(),
                hold.getExpiresAt());
    }
}
