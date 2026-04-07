package github.lms.lemuel.point.application.port.in;

import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;

import java.math.BigDecimal;
import java.util.List;

public interface PointUseCase {

    Point getOrCreatePoint(Long userId);

    PointTransaction earnPoints(EarnPointsCommand command);

    PointTransaction usePoints(UsePointsCommand command);

    PointTransaction cancelEarnedPoints(CancelPointsCommand command);

    PointTransaction cancelUsedPoints(CancelPointsCommand command);

    PointTransaction adminAdjust(Long userId, BigDecimal amount, String description);

    Point getPointBalance(Long userId);

    List<PointTransaction> getTransactionHistory(Long userId);

    record EarnPointsCommand(
            Long userId,
            BigDecimal amount,
            String description,
            String referenceType,
            Long referenceId
    ) {}

    record UsePointsCommand(
            Long userId,
            BigDecimal amount,
            String description,
            String referenceType,
            Long referenceId
    ) {}

    record CancelPointsCommand(
            Long userId,
            BigDecimal amount,
            String description,
            String referenceType,
            Long referenceId
    ) {}
}
