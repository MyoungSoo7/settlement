package github.lms.lemuel.loan.domain;

/**
 * 물건금융 상품 종류 — 캐피탈(여신전문금융)의 리스·할부 3종.
 *
 * <p>셋을 가르는 축은 <b>만기에 물건을 어떻게 하느냐</b>이고, 그 차이가 곧 잔존가치 규칙이 된다.
 *
 * <ul>
 *   <li>{@link #FINANCE_LEASE} 금융리스 — 사실상 물건 값을 나눠 갚는 금융. 만기에 이용자가 인수하는 것이
 *       전제라 잔존가치는 <b>선택</b>(0 이면 전액 회수 구조).</li>
 *   <li>{@link #OPERATING_LEASE} 운용리스 — 만기에 물건을 <b>반환</b>받아 재매각·재리스한다. 반환받을
 *       물건의 값을 리스료로 다 걷지 않으므로 잔존가치가 <b>필수</b>다.</li>
 *   <li>{@link #INSTALLMENT} 할부금융 — 판매자에게 물건 값을 대신 치르고 이용자에게 나눠 받는다.
 *       소유권이 이용자에게 가므로 남길 값이 없다 — 잔존가치 <b>금지</b>(0 만 허용).</li>
 * </ul>
 */
public enum AssetFinanceType {

    /** 금융리스 — 만기 인수 전제. 잔존가치 선택. */
    FINANCE_LEASE("금융리스", true, false),

    /** 운용리스 — 만기 반환 전제. 잔존가치 필수. */
    OPERATING_LEASE("운용리스", true, true),

    /** 할부금융 — 전액 회수. 잔존가치 금지. */
    INSTALLMENT("할부금융", false, false);

    private final String label;
    private final boolean residualAllowed;
    private final boolean residualRequired;

    AssetFinanceType(String label, boolean residualAllowed, boolean residualRequired) {
        this.label = label;
        this.residualAllowed = residualAllowed;
        this.residualRequired = residualRequired;
    }

    /** 사람이 읽는 한글 상품명. */
    public String label() {
        return label;
    }

    /** 잔존가치를 둘 수 있는 상품인가. */
    public boolean allowsResidualValue() {
        return residualAllowed;
    }

    /** 잔존가치가 반드시 있어야 하는 상품인가. */
    public boolean requiresResidualValue() {
        return residualRequired;
    }

    /** 리스인가(할부가 아닌가) — 물건 소유권이 리스사에 남는 상품. */
    public boolean isLease() {
        return this != INSTALLMENT;
    }
}
