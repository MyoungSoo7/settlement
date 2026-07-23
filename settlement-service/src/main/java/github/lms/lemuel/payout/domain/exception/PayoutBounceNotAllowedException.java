package github.lms.lemuel.payout.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.payout.domain.PayoutStatus;

/**
 * 반송(bounce) 대상 부적격 — 세 가지 사유로 발생한다.
 * <ol>
 *   <li>반송은 COMPLETED(자금 이동 성공 후 은행 되돌림)에만 성립한다. 비COMPLETED payout 에 반송을
 *       시도하면 발생한다. 펌뱅킹 호출 실패(SENDING→FAILED)는 반송이 아니라 상태머신의 retry 경로 몫.</li>
 *   <li>재지급 대상 셀러의 계좌가 레지스트리에 등록되어 있지 않다 — 반송 재지급은 계좌 정정 선행을
 *       전제한다(등록 없이는 재지급 계좌를 결정할 수 없다).</li>
 *   <li>등록된 계좌가 반송된 원 payout 의 계좌 스냅샷과 동일하다 — 반송은 계좌 문제로 자금이 되돌아온
 *       사건이므로, 정정되지 않은 동일 계좌로 재지급하면 같은 사유로 재반송될 뿐이다.</li>
 * </ol>
 *
 * <p>기존 {@code IllegalState}(→ 공통 핸들러) 와 동일한 계약으로 매핑되며 위반 상태를 구조적으로 보존한다.
 */
public class PayoutBounceNotAllowedException extends PayoutDomainException {

    private final transient Long payoutId;
    private final transient PayoutStatus actualStatus;

    public PayoutBounceNotAllowedException(Long payoutId, PayoutStatus actualStatus) {
        super(ErrorCode.INVALID_STATE,
                "반송은 COMPLETED 송금에만 가능합니다: payoutId=" + payoutId + ", status=" + actualStatus);
        this.payoutId = payoutId;
        this.actualStatus = actualStatus;
    }

    /** 계좌 정정 선행 미충족(미등록·동일계좌) 사유 — 상태값 없이 사유 메시지로 표현한다. */
    public PayoutBounceNotAllowedException(Long payoutId, String reason) {
        super(ErrorCode.INVALID_STATE, reason + ": payoutId=" + payoutId);
        this.payoutId = payoutId;
        this.actualStatus = null;
    }

    public Long getPayoutId() {
        return payoutId;
    }

    /** 상태 위반 사유가 아니면 {@code null} (계좌 정정 선행 미충족 사유). */
    public PayoutStatus getActualStatus() {
        return actualStatus;
    }
}
