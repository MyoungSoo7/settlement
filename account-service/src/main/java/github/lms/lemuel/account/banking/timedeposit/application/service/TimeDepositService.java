package github.lms.lemuel.account.banking.timedeposit.application.service;

import github.lms.lemuel.account.banking.timedeposit.application.port.in.CloseTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.TimeDepositQueryUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.out.LoadTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.application.port.out.SaveTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAccessDeniedException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositNotFoundException;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 정기예금 개설·해지·조회 응용 서비스 — 수신 서브원장과 전사 GL 을 <b>한 트랜잭션 안에서</b> 함께 움직인다.
 *
 * <h2>GL 전기는 인프로세스다 (Kafka 아님)</h2>
 * account-service 는 이벤트 <b>소비 전용</b> 경계라 스스로 이벤트를 발행하지 않는다(ADR 0026 Option A,
 * ArchUnit 하드스톱). 그래서 수신 상품의 전표는 outbox 를 거치지 않고 같은 서비스 안의
 * {@link RecordAccountEntryUseCase} 를 직접 호출해 올린다 — 서브원장(time_deposits)과 GL(account_entries)이
 * 같은 DB·같은 트랜잭션이므로, 굳이 비동기로 돌려 "서브원장은 닫혔는데 GL 은 아직" 인 창을 만들 이유가 없다.
 *
 * <h2>해지 전표는 두 장이다</h2>
 * 이자 인식(DR 이자비용 / CR 수신부채)과 원리금 지급(DR 수신부채 / CR 현금)을 절대 한 전표에 섞지 않는다.
 * 분리해야 시산표에서 이자비용 누계와 수신부채 증가분이 서로 대응되고, 각 전표가 자기 refType 자연키로
 * 독립 멱등이 된다. 다만 <b>확정이자가 0 이면 이자 전표를 아예 만들지 않는다</b> —
 * {@code AccountEntry} 팩토리는 0·음수 금액을 거부하므로(양수 불변식), 당일 해지·무이자 조건에서
 * 그냥 호출하면 해지 전체가 예외로 죽는다.
 */
@Service
public class TimeDepositService implements OpenTimeDepositUseCase, CloseTimeDepositUseCase, TimeDepositQueryUseCase {

    private final SaveTimeDepositPort saveTimeDepositPort;
    private final LoadTimeDepositPort loadTimeDepositPort;
    private final RecordAccountEntryUseCase recordAccountEntryUseCase;
    private final Clock clock;

    public TimeDepositService(SaveTimeDepositPort saveTimeDepositPort,
                              LoadTimeDepositPort loadTimeDepositPort,
                              RecordAccountEntryUseCase recordAccountEntryUseCase,
                              Clock clock) {
        this.saveTimeDepositPort = saveTimeDepositPort;
        this.loadTimeDepositPort = loadTimeDepositPort;
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
        this.clock = clock;
    }

    /**
     * 개설 — 먼저 저장해 id 를 채번한 뒤 그 id 로 GL 원금 전표를 올린다.
     *
     * <p>순서를 뒤집을 수 없다: 전표의 자연키가 {@code TD-{depositId}} 라서 id 없이는 멱등키가 없다.
     */
    @Override
    @Transactional
    public TimeDeposit open(OpenTimeDepositCommand command) {
        TimeDeposit opened = saveTimeDepositPort.save(TimeDeposit.open(
                command.depositorId(),
                command.productName(),
                command.principal(),
                command.annualRate(),
                command.earlyTerminationRate(),
                command.compounding(),
                command.termMonths(),
                today()));
        recordAccountEntryUseCase.record(AccountEntry.timeDepositOpened(
                opened.getDepositorId(), String.valueOf(opened.getId()), opened.getPrincipal()));
        return opened;
    }

    @Override
    @Transactional
    public TimeDeposit closeOnMaturity(String depositorId, Long depositId) {
        return closeWith(depositorId, depositId, deposit -> deposit.closeOnMaturity(today()));
    }

    @Override
    @Transactional
    public TimeDeposit closeEarly(String depositorId, Long depositId) {
        return closeWith(depositorId, depositId, deposit -> deposit.closeEarly(today()));
    }

    @Override
    @Transactional(readOnly = true)
    public TimeDeposit get(String depositorId, Long depositId) {
        return loadOwned(depositorId, depositId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeDeposit> listMine(String depositorId) {
        return loadTimeDepositPort.findByDepositorId(depositorId);
    }

    /**
     * 만기해지·중도해지 공통 골격 — 소유권 확인 → 도메인 해지 → 저장 → GL 2전표.
     * 두 경로가 갈라지는 지점은 넘겨받은 {@code closing} 함수(=적용 이율 선택)뿐이다.
     */
    private TimeDeposit closeWith(String depositorId, Long depositId, UnaryOperator<TimeDeposit> closing) {
        TimeDeposit closed = saveTimeDepositPort.save(closing.apply(loadOwned(depositorId, depositId)));
        String refId = String.valueOf(closed.getId());
        BigDecimal interest = closed.getSettledInterest();
        if (interest.signum() > 0) {
            recordAccountEntryUseCase.record(
                    AccountEntry.timeDepositInterestSettled(closed.getDepositorId(), refId, interest));
        }
        recordAccountEntryUseCase.record(
                AccountEntry.timeDepositClosed(closed.getDepositorId(), refId, closed.getPayoutAmount()));
        return closed;
    }

    /** 조회 + 소유권 확인을 한 곳으로 모은다 — 소유권 검사를 빠뜨린 경로가 생길 수 없도록. */
    private TimeDeposit loadOwned(String depositorId, Long depositId) {
        TimeDeposit deposit = loadTimeDepositPort.findById(depositId)
                .orElseThrow(() -> new TimeDepositNotFoundException(depositId));
        if (!deposit.ownedBy(depositorId)) {
            throw new TimeDepositAccessDeniedException(depositId);
        }
        return deposit;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
