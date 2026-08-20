package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.UsePointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotSelector;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 사용(차감) — 결제의 POINT 텐더가 부르는 경로.
 *
 * <p>순서가 중요하다:
 * <ol>
 *   <li><b>멱등 단축 반환</b> — 같은 참조로 이미 기록된 사용이면 아무것도 하지 않는다.
 *   <li><b>비관적 락</b>으로 계정을 잡는다. 잔액 확인과 차감 사이에 다른 요청이 끼어들면
 *       같은 포인트가 두 번 쓰인다(재고 read-modify-write 와 같은 함정).
 *   <li>로트에서 만료 임박 순으로 소비 계획을 세우고 <b>총액이 확인된 뒤에만</b> 적용한다.
 *   <li>계정·로트·원장을 같은 트랜잭션에서 저장하고 이벤트를 Outbox 에 넣는다.
 * </ol>
 *
 * <p>잔액 부족({@link InsufficientPointException})은 비즈니스 정상 결과다 — 결제 경로에서는
 * "포인트로는 결제할 수 없다"는 답이며, 재시도 대상이 아니다.
 */
@Service
@Transactional
public class UsePointService implements UsePointUseCase {

    private static final Logger log = LoggerFactory.getLogger(UsePointService.class);

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public UsePointService(PointAccountPort accountPort, PointLotPort lotPort,
                           PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public UsePointResult use(UsePointCommand command) {
        PointAccount account = accountPort.loadForUpdate(command.userId())
                .orElseThrow(() -> new InsufficientPointException(
                        "포인트 계정이 없습니다: userId=" + command.userId(),
                        command.amount(), BigDecimal.ZERO));

        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.USE,
                command.referenceType(), command.referenceId());

        // 같은 tender 를 두 번 차감하지 않는다. L3 UNIQUE 가 최후 방어선이지만,
        // 정상 경로에서 먼저 걸러야 재시도가 예외로 시끄러워지지 않는다.
        if (entryPort.existsByReference(account.getId(), PointEntryType.USE,
                command.referenceType(), command.referenceId())) {
            log.info("포인트 사용 멱등 단축 반환: userId={}, ref={}:{}",
                    command.userId(), command.referenceType(), command.referenceId());
            return new UsePointResult(null, command.amount(), account.getAvailable());
        }

        List<PointLot> lots = lotPort.loadConsumable(account.getId());
        // 계정 잔고를 먼저 줄여 상태 규칙(정지 계정 등)과 잔액 부족을 판정한 뒤 로트를 건드린다.
        account.use(command.amount());
        List<PointLotConsumption> allocations = PointLotSelector.consume(lots, command.amount());

        PointEntry entry = PointEntry.use(account.getId(), command.amount(),
                command.referenceType(), command.referenceId(), sequence, allocations, command.actor());

        PointAccount saved = accountPort.save(account);
        lotPort.saveAll(lots);
        PointEntry appended = entryPort.append(entry);
        eventPort.pointUsed(saved, appended);

        log.info("포인트 사용: userId={}, amount={}, 잔액={}, lots={}",
                command.userId(), command.amount(), saved.getAvailable(), allocations.size());
        return new UsePointResult(appended.getId(), command.amount(), saved.getAvailable());
    }

    /** 잔액 조회 — 결제 화면이 "포인트로 얼마까지 낼 수 있나"를 물을 때. */
    @Transactional(readOnly = true)
    public BigDecimal availableBalance(Long userId) {
        Optional<PointAccount> account = accountPort.load(userId);
        return account.map(PointAccount::getAvailable).orElse(BigDecimal.ZERO);
    }
}
