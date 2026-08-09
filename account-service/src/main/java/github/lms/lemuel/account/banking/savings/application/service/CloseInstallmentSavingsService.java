package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * 적금 해지(만기·중도) — 이자 확정과 원리금 지급을 GL 에 기표한다.
 *
 * <p>만기·중도의 차이는 <b>도메인 안에만</b> 있다(적용 이율·이자 기산 종료일). 이 서비스는 어느 쪽이든
 * 확정된 {@code settledInterest}·{@code payoutAmount} 를 그대로 전표로 옮긴다.
 *
 * <p><b>해지일은 서버 시계가 정한다</b> — 중도해지의 이자 기산 종료일이 곧 해지일이라, 해지일을
 * 요청으로 받으면 미래 날짜 하나로 이자를 부풀릴 수 있다.
 */
@Service
public class CloseInstallmentSavingsService implements CloseInstallmentSavingsUseCase {

    private final LoadInstallmentSavingsPort loadInstallmentSavingsPort;
    private final SaveInstallmentSavingsPort saveInstallmentSavingsPort;
    private final RecordAccountEntryUseCase recordAccountEntryUseCase;
    private final Clock clock;

    public CloseInstallmentSavingsService(LoadInstallmentSavingsPort loadInstallmentSavingsPort,
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
    public InstallmentSavings closeOnMaturity(CloseInstallmentSavingsCommand command) {
        InstallmentSavings savings = loadOwned(command);
        // 해지일은 서버 시계 — 미래 해지일로 중도해지 이자를 부풀리는 경로 차단
        savings.closeOnMaturity(LocalDate.now(clock));
        return postClosingEntries(saveInstallmentSavingsPort.save(savings));
    }

    @Override
    @Transactional
    public InstallmentSavings closeEarly(CloseInstallmentSavingsCommand command) {
        InstallmentSavings savings = loadOwned(command);
        // 해지일은 서버 시계 — 미래 해지일로 중도해지 이자를 부풀리는 경로 차단
        savings.closeEarly(LocalDate.now(clock));
        return postClosingEntries(saveInstallmentSavingsPort.save(savings));
    }

    private InstallmentSavings loadOwned(CloseInstallmentSavingsCommand command) {
        InstallmentSavings savings = loadInstallmentSavingsPort.findById(command.savingsId())
                .orElseThrow(() -> new SavingsNotFoundException(command.savingsId()));
        savings.assertOwnedBy(command.depositorId());
        return savings;
    }

    /**
     * 해지 전표 기표 — 이자 인식 후 원리금 지급 순서.
     *
     * <p>금액이 0 인 전표는 <b>만들지 않는다</b>. {@code AccountEntry} 팩토리가 비양수 금액을
     * {@code NonPositiveEntryAmountException} 으로 거절하기 때문이다:
     * <ul>
     *   <li>이자 0 — 개설 당일 중도해지·이율 0 등에서 실제로 발생한다.</li>
     *   <li>지급액 0 — 한 회차도 내지 않은 계약의 해지. 지급할 자금 자체가 없어 기표할 사건이 없다.</li>
     * </ul>
     */
    private InstallmentSavings postClosingEntries(InstallmentSavings saved) {
        String savingsId = String.valueOf(saved.getId());
        BigDecimal interest = saved.getSettledInterest();
        if (interest != null && interest.signum() > 0) {
            recordAccountEntryUseCase.record(AccountEntry.savingsInterestSettled(
                    saved.getDepositorId(), savingsId, interest));
        }
        BigDecimal payout = saved.getPayoutAmount();
        if (payout != null && payout.signum() > 0) {
            recordAccountEntryUseCase.record(AccountEntry.savingsClosed(
                    saved.getDepositorId(), savingsId, payout));
        }
        return saved;
    }
}
