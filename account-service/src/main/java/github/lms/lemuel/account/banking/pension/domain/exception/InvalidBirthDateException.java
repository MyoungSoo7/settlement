package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.time.LocalDate;

/**
 * 생년월일 불변식 위반 — 가입 시점 기준 미래일 수 없고, 만 15세 이상이어야 한다.
 *
 * <p>생년월일은 수급 개시(만 55세) 판정의 유일한 근거이므로 <b>가입 시점에 한 번</b> 검증하고
 * 그대로 못 박는다. 근로기준법상 취업 최저연령(만 15세) 미만의 퇴직연금 가입은 성립하지 않으므로,
 * "1900년생" 같은 명백한 조작값과 함께 여기서 걸러낸다.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class InvalidBirthDateException extends AccountDomainException {

    private final transient LocalDate birthDate;

    public InvalidBirthDateException(LocalDate birthDate) {
        super(ErrorCode.INVALID_ARGUMENT,
                "생년월일은 가입일보다 미래일 수 없고 가입 시점에 만 15세 이상이어야 합니다: " + birthDate);
        this.birthDate = birthDate;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }
}
