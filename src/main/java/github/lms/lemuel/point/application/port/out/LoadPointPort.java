package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;

import java.util.List;
import java.util.Optional;

public interface LoadPointPort {
    Optional<Point> findByUserId(Long userId);
    List<PointTransaction> findTransactionsByUserId(Long userId);
}
