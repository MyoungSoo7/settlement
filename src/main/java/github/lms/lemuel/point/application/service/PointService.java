package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.PointUseCase;
import github.lms.lemuel.point.application.port.out.LoadPointPort;
import github.lms.lemuel.point.application.port.out.SavePointPort;
import github.lms.lemuel.point.domain.Point;
import github.lms.lemuel.point.domain.PointTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PointService implements PointUseCase {

    private final LoadPointPort loadPointPort;
    private final SavePointPort savePointPort;

    @Override
    public Point getOrCreatePoint(Long userId) {
        return loadPointPort.findByUserId(userId)
                .orElseGet(() -> {
                    Point newPoint = Point.create(userId);
                    Point saved = savePointPort.save(newPoint);
                    log.info("새 포인트 계정 생성: userId={}", userId);
                    return saved;
                });
    }

    @Override
    public PointTransaction earnPoints(EarnPointsCommand command) {
        Point point = getOrCreatePoint(command.userId());
        PointTransaction tx = point.earn(
                command.amount(), command.description(),
                command.referenceType(), command.referenceId()
        );
        savePointPort.save(point);
        PointTransaction saved = savePointPort.saveTransaction(tx);
        log.info("포인트 적립: userId={}, amount={}, balance={}", command.userId(), command.amount(), point.getBalance());
        return saved;
    }

    @Override
    public PointTransaction usePoints(UsePointsCommand command) {
        Point point = getOrCreatePoint(command.userId());
        PointTransaction tx = point.use(
                command.amount(), command.description(),
                command.referenceType(), command.referenceId()
        );
        savePointPort.save(point);
        PointTransaction saved = savePointPort.saveTransaction(tx);
        log.info("포인트 사용: userId={}, amount={}, balance={}", command.userId(), command.amount(), point.getBalance());
        return saved;
    }

    @Override
    public PointTransaction cancelEarnedPoints(CancelPointsCommand command) {
        Point point = getOrCreatePoint(command.userId());
        PointTransaction tx = point.cancelEarn(
                command.amount(), command.description(),
                command.referenceType(), command.referenceId()
        );
        savePointPort.save(point);
        PointTransaction saved = savePointPort.saveTransaction(tx);
        log.info("포인트 적립 취소: userId={}, amount={}, balance={}", command.userId(), command.amount(), point.getBalance());
        return saved;
    }

    @Override
    public PointTransaction cancelUsedPoints(CancelPointsCommand command) {
        Point point = getOrCreatePoint(command.userId());
        PointTransaction tx = point.cancelUse(
                command.amount(), command.description(),
                command.referenceType(), command.referenceId()
        );
        savePointPort.save(point);
        PointTransaction saved = savePointPort.saveTransaction(tx);
        log.info("포인트 사용 취소: userId={}, amount={}, balance={}", command.userId(), command.amount(), point.getBalance());
        return saved;
    }

    @Override
    public PointTransaction adminAdjust(Long userId, BigDecimal amount, String description) {
        Point point = getOrCreatePoint(userId);
        PointTransaction tx = point.adminAdjust(amount, description);
        savePointPort.save(point);
        PointTransaction saved = savePointPort.saveTransaction(tx);
        log.info("관리자 포인트 조정: userId={}, amount={}, balance={}", userId, amount, point.getBalance());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Point getPointBalance(Long userId) {
        return loadPointPort.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("포인트 정보를 찾을 수 없습니다. userId=" + userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointTransaction> getTransactionHistory(Long userId) {
        return loadPointPort.findTransactionsByUserId(userId);
    }
}
