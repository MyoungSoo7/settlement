package github.lms.lemuel.account.banking.savings.application.port.in;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;

import java.math.BigDecimal;

/**
 * 적금 회차 납입 인바운드 포트.
 *
 * <p>납입 1건 = GL 전표 1건({@code AccountEntry.savingsInstallmentPaid},
 * DR CASH / CR INSTALLMENT_SAVINGS_LIABILITY). 전표는 <b>같은 트랜잭션 안에서 직접</b>
 * {@code RecordAccountEntryUseCase} 로 기표한다 — 이벤트 발행 경로가 아니다.
 *
 * <p>커맨드에 <b>납입일이 없다</b> — 납입일은 서버 시계(Clock)가 정한다. 소급 납입일은 그 회차의
 * 예치일수를 늘려 이자를 부풀리는, 사용자가 스스로 돈을 만들어내는 경로다.
 */
public interface PayInstallmentUseCase {

    InstallmentSavings pay(PayInstallmentCommand command);

    /**
     * @param savingsId   경로에서 온 계약 id
     * @param depositorId <b>JWT 주체에서 파생한</b> 예금주 — 계약의 예금주와 다르면 403
     */
    record PayInstallmentCommand(Long savingsId,
                                 String depositorId,
                                 int round,
                                 BigDecimal amount) {
    }
}
