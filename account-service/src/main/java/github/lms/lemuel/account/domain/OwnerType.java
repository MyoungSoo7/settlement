package github.lms.lemuel.account.domain;

/**
 * 분개 귀속 주체의 종류.
 *
 * <p>SELLER 는 이커머스 셀러(ownerId = sellerId 문자열), CORPORATE 는 상장 법인(ownerId = stockCode),
 * BORROWER 는 담보/개인신용 대출 차주(ownerId = borrowerUserId 숫자 문자열 — 개인·법인 공통,
 * 상장 여부와 무관해 stockCode 로 표현할 수 없다). 대출·투자·정산 이벤트가 어느 주체의 원장으로
 * 집계되는지를 구분한다.
 */
public enum OwnerType {
    SELLER,
    CORPORATE,
    BORROWER,
    /** 수신 상품(정기예금·적금·퇴직연금) 가입자 — ownerId = userId 숫자 문자열. 제도별로 쪼개지 않는다. */
    DEPOSITOR
}
