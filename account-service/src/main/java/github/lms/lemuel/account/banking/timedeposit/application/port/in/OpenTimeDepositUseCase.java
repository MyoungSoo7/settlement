package github.lms.lemuel.account.banking.timedeposit.application.port.in;

import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;

import java.math.BigDecimal;

/**
 * 정기예금 개설 인바운드 포트.
 *
 * <p>커맨드에 <b>개설일이 없다</b> — 개설일은 서버 시계(Clock)가 정한다. 클라이언트가 날짜를 고를 수
 * 있으면 과거 날짜로 계좌를 열어 없던 이자를 만들어낼 수 있다.
 */
public interface OpenTimeDepositUseCase {

    TimeDeposit open(OpenTimeDepositCommand command);

    /**
     * @param depositorId 예금주 식별자 — 반드시 <b>JWT 주체</b>에서 파생된 값이어야 한다(요청 본문 금지, IDOR)
     */
    record OpenTimeDepositCommand(String depositorId,
                                  String productName,
                                  BigDecimal principal,
                                  BigDecimal annualRate,
                                  BigDecimal earlyTerminationRate,
                                  Compounding compounding,
                                  int termMonths) {
    }
}
