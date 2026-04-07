package github.lms.lemuel.point.adapter.in.web.dto;

import github.lms.lemuel.point.domain.Point;

import java.math.BigDecimal;

public record PointResponse(
        Long id,
        Long userId,
        BigDecimal balance,
        BigDecimal totalEarned,
        BigDecimal totalUsed
) {
    public static PointResponse from(Point point) {
        return new PointResponse(
                point.getId(),
                point.getUserId(),
                point.getBalance(),
                point.getTotalEarned(),
                point.getTotalUsed()
        );
    }
}
