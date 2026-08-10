package github.lms.lemuel.deposit.domain;

/**
 * 예치금 선점(Hold) 의 보유자 유형.
 *
 * <p>카드 승인·대출 실행 등이 재원을 선점할 때 어떤 도메인에서 온 hold 인지를 식별한다.
 * (holder_type, holder_reference) 쌍이 hold 의 자연키이자 멱등 키다.
 */
public enum DepositHolderType {
    /** 카드 승인(authorizationId) 에 의한 선점. */
    CARD_AUTHORIZATION,
    /** 선정산 대출 실행에 의한 선점. */
    LOAN_DISBURSEMENT,
    /** 투자 실행에 의한 선점. */
    INVESTMENT_EXECUTION,
    /** 기타(수동 관리 등). */
    MANUAL
}
