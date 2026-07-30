package github.lms.lemuel.loan.domain;

/**
 * {@link SecuredLoan} 이 수용하는 대출 상품 유형.
 *
 * <p>Phase 1 은 담보형 1종(주택담보)과 무담보형 1종(개인신용)으로 시작했고 — 담보 유무가 갈리는 두 축을
 * 모두 덮어, 담보를 optional 로 두는 설계가 실제로 성립하는지 그 단계에서 검증됐다 — Phase 2 에서
 * 금융자산 담보형이 합류했다(담보 유형은 예금·채권·주식 3종, 인정비율은 정책 표).
 */
public enum LoanProductType {
    /** 주택담보대출 — 담보 필수, 한도는 유효담보가치×LTV. */
    MORTGAGE,
    /** 금융자산담보대출 — 담보 필수(예금·채권·주식 계열), 한도는 유효담보가치×유형별 인정비율. */
    FINANCIAL_ASSET,
    /** 개인신용대출 — 담보 없음, 외부 CB 점수 스냅샷으로 심사. */
    PERSONAL_CREDIT;

    /** 담보가 반드시 있어야 하는 상품인지. */
    public boolean requiresCollateral() {
        return this != PERSONAL_CREDIT;
    }
}
