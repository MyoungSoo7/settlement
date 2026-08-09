package github.lms.lemuel.account.banking.savings.application.port.in;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;

import java.math.BigDecimal;

/**
 * 적금 신규 개설 인바운드 포트.
 *
 * <p>개설 시점에는 GL 전표가 없다 — 적금은 예금과 달리 개설 시 들어오는 원금이 없기 때문이다.
 * 수신부채는 첫 회차 납입({@code savingsInstallmentPaid})부터 움직인다.
 *
 * <p>커맨드에 <b>개설일이 없다</b> — 개설일은 서버 시계(Clock)가 정한다. 개설일을 클라이언트가 고르면
 * 만기일과 모든 회차의 기일이 통째로 옮겨져, 소급 개설만으로 없던 이자가 생긴다.
 */
public interface OpenInstallmentSavingsUseCase {

    InstallmentSavings open(OpenInstallmentSavingsCommand command);

    /**
     * @param depositorId          <b>JWT 주체에서 파생한</b> 예금주 — 요청 본문·경로에서 받지 않는다(IDOR).
     * @param monthlyAmount        FIXED 필수(양수) / FLEXIBLE 이면 null
     * @param paymentLimit         FLEXIBLE 선택(회차 한도) / FIXED 이면 null
     * @param annualRate           약정 연이율 [0,1)
     * @param earlyTerminationRate 중도해지 연이율 [0,1)
     */
    record OpenInstallmentSavingsCommand(String depositorId,
                                         String productName,
                                         SavingsType savingsType,
                                         BigDecimal monthlyAmount,
                                         BigDecimal paymentLimit,
                                         BigDecimal annualRate,
                                         BigDecimal earlyTerminationRate,
                                         int termMonths) {
    }
}
