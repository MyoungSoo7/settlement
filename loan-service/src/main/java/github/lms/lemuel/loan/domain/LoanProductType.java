package github.lms.lemuel.loan.domain;

/**
 * {@link SecuredLoan} 이 수용하는 대출 상품 유형.
 *
 * <p>Phase 1 은 담보형 1종(주택담보)과 무담보형 1종(개인신용)을 다룬다 — 담보 유무가 갈리는 두 축을
 * 모두 덮어, 담보를 optional 로 두는 설계가 실제로 성립하는지 이 단계에서 검증된다.
 * 보증기관 보증부·금융자산 담보는 Phase 2 이월.
 */
public enum LoanProductType {
    /** 주택담보대출 — 담보 필수, 한도는 유효담보가치×LTV. */
    MORTGAGE,
    /** 개인신용대출 — 담보 없음, 외부 CB 점수 스냅샷으로 심사. */
    PERSONAL_CREDIT;

    /** 담보가 반드시 있어야 하는 상품인지. */
    public boolean requiresCollateral() {
        return this == MORTGAGE;
    }
}
