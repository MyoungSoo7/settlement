package github.lms.lemuel.account.domain;

/**
 * 분개 귀속 주체의 종류.
 *
 * <p>SELLER 는 이커머스 셀러(ownerId = sellerId 문자열), CORPORATE 는 상장 법인(ownerId = stockCode).
 * 대출·투자·정산 이벤트가 어느 주체의 원장으로 집계되는지를 구분한다.
 */
public enum OwnerType {
    SELLER,
    CORPORATE
}
