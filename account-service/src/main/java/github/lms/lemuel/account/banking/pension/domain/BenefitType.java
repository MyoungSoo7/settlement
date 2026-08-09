package github.lms.lemuel.account.banking.pension.domain;

/**
 * 퇴직급여 수급 형태와 그 <b>수급 요건</b>.
 *
 * <p>요건을 상수 속성으로 들고 있어, 애그리게이트는 "어떤 형태냐"를 분기하지 않고
 * {@link #minimumAge()}·{@link #minimumSubscribedYears()} 두 값만 비교한다.
 *
 * <ul>
 *   <li>{@code ANNUITY}(연금) — 만 55세 이상 <b>이고</b> 가입기간 10년 이상.</li>
 *   <li>{@code LUMP_SUM}(일시금) — 만 55세 이상이면 되고 가입기간 요건은 없다.</li>
 * </ul>
 */
public enum BenefitType {

    /** 연금 수령 — 만 55세 + 가입기간 10년. */
    ANNUITY(55, 10),

    /** 일시금 수령 — 만 55세만 충족하면 된다(가입기간 무관). */
    LUMP_SUM(55, 0);

    private final int minimumAge;
    private final int minimumSubscribedYears;

    BenefitType(int minimumAge, int minimumSubscribedYears) {
        this.minimumAge = minimumAge;
        this.minimumSubscribedYears = minimumSubscribedYears;
    }

    /** 수급 개시 최소 연령(만 나이). */
    public int minimumAge() {
        return minimumAge;
    }

    /** 수급 개시 최소 가입기간(년) — 일시금은 0. */
    public int minimumSubscribedYears() {
        return minimumSubscribedYears;
    }

    /** 연령·가입기간이 이 수급 형태의 요건을 모두 충족하는가. */
    public boolean isEligible(int age, int subscribedYears) {
        return age >= minimumAge && subscribedYears >= minimumSubscribedYears;
    }
}
