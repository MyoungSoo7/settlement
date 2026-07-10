package github.lms.lemuel.account.domain;

/**
 * 계정의 정상 잔액 방향(계정 성격).
 *
 * <p>차변성(DEBIT) 계정은 차변 증가·대변 감소, 대변성(CREDIT) 계정은 대변 증가·차변 감소.
 * {@link AccountSummary} 가 owner 별 잔액을 이 방향에 맞춰 부호(DR−CR / CR−DR)로 산출한다.
 */
public enum AccountSide {
    DEBIT,
    CREDIT
}
