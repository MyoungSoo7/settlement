package github.lms.lemuel.deposit.adapter.in.web.dto;

import github.lms.lemuel.deposit.domain.DepositHolderType;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 미해소 부족분 노출 DTO.
 *
 * <p>요청액·적용액·부족분을 <b>셋 다</b> 내보낸다. 부족분만 주면 "3천 중 2천이 모자란 건"과
 * "2천 전액이 모자란 건"이 같은 화면으로 보여, 운영자가 어느 쪽을 먼저 손대야 할지 판단할 수 없다.
 */
public record ShortfallResponse(
        Long id,
        Long sellerId,
        DepositHolderType holderType,
        String holderReference,
        BigDecimal requestedAmount,
        BigDecimal appliedAmount,
        BigDecimal shortfallAmount,
        DepositShortfallStatus status,
        Long sourceHoldId,
        OffsetDateTime occurredAt) {

    public static ShortfallResponse from(DepositOffsetShortfall s) {
        return new ShortfallResponse(
                s.getId(), s.getSellerId(), s.getHolderType(), s.getHolderReference(),
                s.getRequestedAmount(), s.getAppliedAmount(), s.getShortfallAmount(),
                s.getStatus(), s.getSourceHoldId(), s.getOccurredAt());
    }
}
