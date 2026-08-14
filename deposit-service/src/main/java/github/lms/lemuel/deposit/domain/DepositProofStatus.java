package github.lms.lemuel.deposit.domain;

/**
 * 예치금 증빙 대사 상태 (ADR 0036 확산 — card·insurance·loan 과 같은 전이표).
 *
 * <pre>
 * EXTRACTED ──기표 시점 지연 대사──▶ MATCHED | MISMATCHED | NEEDS_REVIEW
 * NEEDS_REVIEW ──운영자 리뷰──▶ MATCHED | MISMATCHED
 * </pre>
 *
 * <p><b>다른 확산처와의 차이</b>: deposit 의 수기 기표는 선행 애그리거트가 없어(즉시 반영 구조)
 * 첨부 시점에는 대조할 정본 값이 없다. 그래서 EXTRACTED 는 과도 상태가 아니라 <b>기표 대기 상태</b>이며,
 * 자동 대사는 기표({@code credit}/{@code debit}) 트랜잭션 안에서 요청 값과 대조해 실행된다
 * (지연 대사 — {@code DepositProofGate}). 첨부 시점에는 신뢰도 미달만 즉시 NEEDS_REVIEW 로 보낸다.
 *
 * <p>MATCHED/MISMATCHED 는 종결 — 번복은 새 증빙 첨부로만 한다.
 */
public enum DepositProofStatus {

    /** OCR 추출 완료, 기표 대기 — 기표 시점에 요청 값과 지연 대사된다 */
    EXTRACTED,
    /** 기표 요청 값과 대사 일치(또는 운영자 육안 확정) — 기표 게이트 통과 근거 */
    MATCHED,
    /** 기표 요청 값과 불일치 — 이 증빙으로는 기표 불가 */
    MISMATCHED,
    /** 신뢰도 미달·이체일 판독 불가 — 운영자 육안 리뷰 대기 */
    NEEDS_REVIEW;

    public boolean canTransitionTo(DepositProofStatus next) {
        return switch (this) {
            case EXTRACTED -> next == MATCHED || next == MISMATCHED || next == NEEDS_REVIEW;
            case NEEDS_REVIEW -> next == MATCHED || next == MISMATCHED;
            case MATCHED, MISMATCHED -> false;
        };
    }

    public boolean isTerminal() {
        return this == MATCHED || this == MISMATCHED;
    }
}
