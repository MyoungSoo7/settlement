package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 계약의 예금주가 아닌 주체가 접근 — IDOR 차단의 마지막 관문.
 *
 * <p>예금주 식별자는 요청(경로·쿼리·본문)에서 오지 않고 JWT 주체에서만 파생되지만, 계약 id 는
 * 경로에서 온다. 즉 "남의 savingsId + 내 토큰" 조합이 항상 만들어질 수 있고, 그걸 여기서 잘라낸다.
 *
 * <p>{@code ErrorCode.ACCESS_DENIED}(403) — 존재 여부를 알려주지 않기 위해 404 로 위장하지 않는다.
 * 계약 id 는 추측 가능한 연번이라 어차피 존재 여부가 은닉되지 않으며, 403 이 감사 로그에서 더 명확하다.
 */
public class SavingsAccessDeniedException extends AccountSavingsDomainException {

    private final Long savingsId;

    public SavingsAccessDeniedException(Long savingsId) {
        super(ErrorCode.ACCESS_DENIED, "본인의 적금이 아닙니다: " + savingsId);
        this.savingsId = savingsId;
    }

    public Long getSavingsId() {
        return savingsId;
    }
}
