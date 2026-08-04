package github.lms.lemuel.card.domain;

/**
 * 지출보고서 생명주기 상태.
 *
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED
 *                       │
 *                    reject
 *                       │
 *                       ▼
 *                   REJECTED ──submit──▶ SUBMITTED (재제출 가능)
 * </pre>
 *
 * <p>승인 경로({@code AuthorizeCardUseCase})와 완전 비결합 — {@code DeclineReason} 에
 * {@code PENDING_APPROVAL} 같은 값을 추가하지 않는다(계약 불변식).
 */
public enum ExpenseReportStatus {
    /** 자동 생성(매입 확정 이벤트 소비 시) — 임직원이 증빙을 제출하기 전 상태 */
    DRAFT,
    /** 임직원이 영수증·카테고리·메모를 첨부해 제출한 상태 */
    SUBMITTED,
    /** 관리자(MANAGER/OWNER)가 승인 완료 */
    APPROVED,
    /** 관리자가 반려 — 임직원이 수정 후 재제출 가능 */
    REJECTED;

    /** {@code DRAFT} → {@code SUBMITTED} 전이 가능 여부 */
    public boolean canSubmit() {
        return this == DRAFT || this == REJECTED;
    }

    /** {@code SUBMITTED} → {@code APPROVED} 전이 가능 여부 */
    public boolean canApprove() {
        return this == SUBMITTED;
    }

    /** {@code SUBMITTED} → {@code REJECTED} 전이 가능 여부 */
    public boolean canReject() {
        return this == SUBMITTED;
    }
}
