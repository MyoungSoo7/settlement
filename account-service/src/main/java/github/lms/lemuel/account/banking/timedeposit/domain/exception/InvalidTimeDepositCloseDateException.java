package github.lms.lemuel.account.banking.timedeposit.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.time.LocalDate;

/**
 * 해지일이 개설일보다 앞섬 — 음수 경과일수는 곧 음수 이자이고, 음수 금액 전표는 GL 이 거부한다.
 *
 * <p>{@code null} 해지일도 같은 이유로 거절한다(경과일수를 셀 수 없음).
 * {@code ErrorCode.INVALID_STATE}(400) 로 매핑한다(공통 카탈로그에 banking 전용 코드 부재 — 재사용).
 */
public class InvalidTimeDepositCloseDateException extends AccountDomainException {

    private final transient LocalDate openedOn;
    private final transient LocalDate closedOn;

    public InvalidTimeDepositCloseDateException(LocalDate openedOn, LocalDate closedOn) {
        super(ErrorCode.INVALID_STATE,
                "해지일은 개설일 이후여야 합니다: openedOn=" + openedOn + ", closedOn=" + closedOn);
        this.openedOn = openedOn;
        this.closedOn = closedOn;
    }

    public LocalDate getOpenedOn() {
        return openedOn;
    }

    public LocalDate getClosedOn() {
        return closedOn;
    }
}
