package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 존재하지 않는 퇴직연금 계약 참조 → {@code RETIREMENT_PENSION_NOT_FOUND}(404).
 *
 * <p>"없다"(404)와 "잘못 보냈다"(400)를 섞지 않는다 — 클라이언트가 재시도할지 요청을 고칠지 판단하는
 * 근거가 이 구분이다. 반대로 <b>남의 계약</b>은 404 가 아니라 {@link PensionAccessDeniedException}(403)
 * 이다. 존재 여부를 감추려고 404 로 위장하면 정상 사용자의 오탈자와 침해 시도가 로그에서 구분되지 않는다.
 */
public class PensionNotFoundException extends AccountDomainException {

    private final Long pensionId;

    public PensionNotFoundException(Long pensionId) {
        super(ErrorCode.RETIREMENT_PENSION_NOT_FOUND, "퇴직연금 계약을 찾을 수 없습니다: " + pensionId);
        this.pensionId = pensionId;
    }

    public Long getPensionId() {
        return pensionId;
    }
}
