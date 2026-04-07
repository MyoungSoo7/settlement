package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;

public interface SavePointPort {
    Point save(Point point);
    PointTransaction saveTransaction(PointTransaction transaction);
}
