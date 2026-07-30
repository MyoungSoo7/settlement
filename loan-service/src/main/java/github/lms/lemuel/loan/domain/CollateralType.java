package github.lms.lemuel.loan.domain;

/**
 * 담보 유형. Phase 2 에서 보증부·금융자산이 합류해 3계열이 되었다.
 *
 * <p>금융자산을 하나로 묶지 않고 예금·채권·주식으로 <b>쪼갠 이유</b>: 담보인정비율이 자산 종류마다
 * 크게 다르고(예금 95% vs 주식 60%), 이를 한 유형 안의 파라미터로 두면 인정비율이 담보 행마다 달라져
 * 정책이 데이터로 새어 나간다. 유형을 나누면 인정비율이 정책 테이블 한 곳에 남는다.
 *
 * <p>대신 <b>계열(Category)</b>로 묶어 계열 단위 행위를 표현한다 — 마진콜은 시가 변동이 있는
 * 금융자산 계열만, 대위변제는 보증기관이 있는 보증부 계열만 해당한다.
 */
public enum CollateralType {
    /** 부동산(주택) 담보. */
    REAL_ESTATE(Category.REAL_ESTATE),
    /** 보증기관 보증서 — 평가액은 보증서상 보증금액이다. */
    GUARANTEE(Category.GUARANTEE),
    /** 예금·예치금. */
    DEPOSIT(Category.FINANCIAL_ASSET),
    /** 채권. */
    BOND(Category.FINANCIAL_ASSET),
    /** 주식·수익증권. */
    EQUITY(Category.FINANCIAL_ASSET);

    /** 담보 계열 — 유형별 파라미터가 아니라 <b>행위</b>가 갈리는 축. */
    public enum Category {
        REAL_ESTATE,
        GUARANTEE,
        FINANCIAL_ASSET
    }

    private final Category category;

    CollateralType(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** 시가가 변동해 재평가·마진콜 대상인지 — 금융자산 계열만 해당. */
    public boolean supportsMarginCall() {
        return category == Category.FINANCIAL_ASSET;
    }

    /** 부도 시 보증기관 대위변제로 회수하는지 — 보증부 계열만 해당. */
    public boolean supportsSubrogation() {
        return category == Category.GUARANTEE;
    }

    /** 부도 시 담보물을 처분해 회수하는지 — 보증부는 처분 대상이 아니다(보증금 청구). */
    public boolean supportsDisposal() {
        return category != Category.GUARANTEE;
    }
}
