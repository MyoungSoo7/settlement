package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase;
import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 적금 회차 납입 — 도메인 검증 → 저장 → GL 기표를 <b>한 트랜잭션</b> 안에서 처리한다.
 *
 * <p>서브원장(savings_installments)과 GL(account_entries)이 같은 트랜잭션에서 함께 움직여야
 * "회차는 남았는데 전표가 없는" 상태가 생기지 않는다. 이벤트 발행(outbox·Kafka)은 쓰지 않는다 —
 * account-service 는 소비 전용 경계이고, 여기 GL 기표는 프로세스 내부 호출이다.
 *
 * <p><b>납입일은 서버 시계가 정한다</b> — 납입일을 요청으로 받으면 사용자가 과거 날짜를 넣어 그 회차의
 * 예치일수를 늘릴 수 있고, 이자는 예치일수에 정비례하므로 그대로 "돈을 만들어내는" 경로가 된다.
 * 자기 계약에 대한 조작이라 소유권 검사(IDOR 가드)로는 막히지 않는다.
 */
@Service
public class PayInstallmentService implements PayInstallmentUseCase {

    private final LoadInstallmentSavingsPort loadInstallmentSavingsPort;
    private final SaveInstallmentSavingsPort saveInstallmentSavingsPort;
    private final RecordAccountEntryUseCase recordAccountEntryUseCase;
    private final Clock clock;

    public PayInstallmentService(LoadInstallmentSavingsPort loadInstallmentSavingsPort,
                                 SaveInstallmentSavingsPort saveInstallmentSavingsPort,
                                 RecordAccountEntryUseCase recordAccountEntryUseCase,
                                 Clock clock) {
        this.loadInstallmentSavingsPort = loadInstallmentSavingsPort;
        this.saveInstallmentSavingsPort = saveInstallmentSavingsPort;
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InstallmentSavings pay(PayInstallmentCommand command) {
        InstallmentSavings savings = loadInstallmentSavingsPort.findById(command.savingsId())
                .orElseThrow(() -> new SavingsNotFoundException(command.savingsId()));
        savings.assertOwnedBy(command.depositorId());

        // 납입일은 서버 시계 — 소급 납입으로 예치일수(=이자)를 부풀리는 경로 차단
        savings.pay(command.round(), command.amount(), LocalDate.now(clock));
        InstallmentSavings saved = saveInstallmentSavingsPort.save(savings);

        // 자연키 refId = SV-{savingsId}-{round} — 같은 회차 재요청은 GL 에서 멱등 흡수된다.
        recordAccountEntryUseCase.record(AccountEntry.savingsInstallmentPaid(
                saved.getDepositorId(),
                String.valueOf(saved.getId()),
                command.round(),
                command.amount()));
        return saved;
    }
}
