package github.lms.lemuel.account.banking.savings.application.port.in;

import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;

/**
 * 적금 해지(만기·중도) 인바운드 포트.
 *
 * <p>해지 1건이 만드는 GL 전표는 최대 2건이다:
 * <ol>
 *   <li>{@code savingsInterestSettled} — DR INTEREST_EXPENSE / CR INSTALLMENT_SAVINGS_LIABILITY.
 *       <b>확정이자가 0 이면 전표 자체를 만들지 않는다</b>(팩토리가 0·음수 금액을 거절한다).</li>
 *   <li>{@code savingsClosed} — DR INSTALLMENT_SAVINGS_LIABILITY / CR CASH (원리금 지급).</li>
 * </ol>
 * 순서가 중요하다 — 이자를 먼저 부채로 인식해야 지급 전표가 부채를 정확히 0 으로 닫는다.
 *
 * <p>커맨드에 <b>해지일이 없다</b> — 해지일은 서버 시계(Clock)가 정한다. 해지일을 미래로 보내면
 * 중도해지 이자 기산 종료일이 밀려 이자가 부풀려진다.
 */
public interface CloseInstallmentSavingsUseCase {

    /** 만기 해지 — 약정이율, 이자 기산 종료일 = 만기일. */
    InstallmentSavings closeOnMaturity(CloseInstallmentSavingsCommand command);

    /** 중도 해지 — 중도해지이율, 이자 기산 종료일 = 해지일. */
    InstallmentSavings closeEarly(CloseInstallmentSavingsCommand command);

    /**
     * @param depositorId <b>JWT 주체에서 파생한</b> 예금주 — 계약의 예금주와 다르면 403
     */
    record CloseInstallmentSavingsCommand(Long savingsId, String depositorId) {
    }
}
