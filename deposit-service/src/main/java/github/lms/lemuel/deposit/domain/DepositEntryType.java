package github.lms.lemuel.deposit.domain;

/**
 * 예치금 원장 엔트리 유형.
 *
 * <p>append-only 원장({@code deposit_entries})의 각 행이 어떤 경제 사건을 나타내는지 식별한다.
 * 모든 잔고 변경은 반드시 엔트리 한 건 이상으로 기록되어야 한다(대사 가능성).
 */
public enum DepositEntryType {
    /** 입금 — 정산 확정 등으로 available 증가. */
    CREDIT,
    /** 출금 — payout 실행 등으로 available 감소. */
    DEBIT,
    /** 잠금 — hold 설정으로 available→locked 이동. */
    HOLD,
    /** 잠금 해제 — hold 만료·취소로 locked→available 이동. */
    RELEASE,
    /** 상계 — locked 또는 available 에서 카드 청구액 직접 차감. */
    OFFSET
}
