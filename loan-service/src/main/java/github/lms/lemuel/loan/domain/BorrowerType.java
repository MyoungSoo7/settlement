package github.lms.lemuel.loan.domain;

/**
 * 차주 유형. 담보대출은 개인·법인이 모두 차주가 될 수 있어, 상장사 종목코드(6자리)에 정체성을 묶은
 * {@link CorporateLoan} 과 달리 차주 유형을 명시적으로 구분한다.
 */
public enum BorrowerType {
    /** 개인 차주 — 사업자등록번호를 갖지 않는다. */
    INDIVIDUAL,
    /** 법인·개인사업자 차주 — 사업자등록번호(10자리)가 필수다. */
    CORPORATE
}
