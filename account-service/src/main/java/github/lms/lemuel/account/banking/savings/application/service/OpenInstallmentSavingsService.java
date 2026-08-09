package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 적금 개설 — 계약 조건 검증은 전부 도메인({@link InstallmentSavings#open})이 하고, 이 서비스는
 * 만들어 저장하는 일만 한다.
 *
 * <p><b>GL 전표 없음</b>: 개설 시점엔 자금 이동이 없다(적금은 예금과 달리 개설 원금이 없다).
 * 여기서 억지로 0 원 전표를 만들면 {@code AccountEntry} 팩토리가 비양수 금액으로 거절한다.
 *
 * <p><b>개설일은 서버 시계가 정한다</b> — 클라이언트가 개설일을 고르면 만기일과 모든 회차의 기일이
 * 통째로 옮겨져, 소급 개설만으로 예치일수가 늘어난 이자를 만들어낼 수 있다.
 */
@Service
public class OpenInstallmentSavingsService implements OpenInstallmentSavingsUseCase {

    private final SaveInstallmentSavingsPort saveInstallmentSavingsPort;
    private final Clock clock;

    public OpenInstallmentSavingsService(SaveInstallmentSavingsPort saveInstallmentSavingsPort,
                                         Clock clock) {
        this.saveInstallmentSavingsPort = saveInstallmentSavingsPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InstallmentSavings open(OpenInstallmentSavingsCommand command) {
        InstallmentSavings savings = InstallmentSavings.open(
                command.depositorId(),
                command.productName(),
                command.savingsType(),
                command.monthlyAmount(),
                command.paymentLimit(),
                command.annualRate(),
                command.earlyTerminationRate(),
                command.termMonths(),
                LocalDate.now(clock));   // 개설일은 서버 시계 — 소급 개설로 이자를 만들어내는 경로 차단
        return saveInstallmentSavingsPort.save(savings);
    }
}
