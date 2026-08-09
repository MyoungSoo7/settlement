package github.lms.lemuel.account.banking.timedeposit.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 이미 해지된 정기예금을 다시 해지하려 함 — CLOSED 는 종단 상태다.
 *
 * <p>재해지를 허용하면 이자가 두 번 확정되고 원리금 지급 전표가 두 벌 올라가 수신부채가 음수로
 * 내려간다(GL 통제계정 파손). 자연키 {@code (source_topic, ref_type, ref_id)} UNIQUE 가 최종 방어이긴
 * 하나, 그 방어에 기대면 절반만 전기된 상태로 트랜잭션이 오염되므로 도메인에서 먼저 끊는다.
 *
 * <p>{@code ErrorCode.INVALID_STATE}(400) 로 매핑한다(공통 카탈로그에 banking 전용 코드 부재 — 재사용).
 */
public class TimeDepositAlreadyClosedException extends AccountDomainException {

    private final transient Long depositId;

    public TimeDepositAlreadyClosedException(Long depositId) {
        super(ErrorCode.INVALID_STATE, "이미 해지된 정기예금입니다: " + depositId);
        this.depositId = depositId;
    }

    public Long getDepositId() {
        return depositId;
    }
}
