package github.lms.lemuel.point.adapter.in.web.dto;

import github.lms.lemuel.point.domain.PointTransaction;
import github.lms.lemuel.point.domain.PointTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PointTransactionResponse(
        Long id,
        Long userId,
        Long pointId,
        PointTransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        String referenceType,
        Long referenceId,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
    public static PointTransactionResponse from(PointTransaction tx) {
        return new PointTransactionResponse(
                tx.getId(),
                tx.getUserId(),
                tx.getPointId(),
                tx.getType(),
                tx.getAmount(),
                tx.getBalanceAfter(),
                tx.getDescription(),
                tx.getReferenceType(),
                tx.getReferenceId(),
                tx.getExpiresAt(),
                tx.getCreatedAt()
        );
    }
}
