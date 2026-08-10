package github.lms.lemuel.insurance.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 일반지급 도메인 상수 (D-G3).
 *
 * <p><b>해약환급률표</b>: {@link #SURRENDER_REFUND_RATE_TIERS} 선언이 이 표의 유일한 정의
 * 지점이다 — 다른 어떤 비-테스트 코드도 같은 구간·요율을 리터럴로 다시 적지 않는다.
 * 경과월수 m 에 대해 floor-entry(구간 하한 이하 최대 키)의 요율을 적용한다:
 * <ul>
 *   <li>m &lt; 12 → 0% (초기 해약공제 — 환급 없음, payout 미생성)</li>
 *   <li>12 ≤ m &lt; 36 → 40%</li>
 *   <li>36 ≤ m &lt; 60 → 60%</li>
 *   <li>60 ≤ m &lt; 84 → 75%</li>
 *   <li>m ≥ 84 → 85%</li>
 * </ul>
 *
 * <p>구간 경계는 D6 환수 창구 값({@code CommissionConstants.CLAWBACK_WINDOW_MONTHS})과
 * 무관한 값만 쓴다 — 환수 창구 리터럴 단일 선언 규율(ClawbackConstantDisciplineTest)과
 * 충돌하지 않는다.
 *
 * <p>금액 스케일은 {@link CommissionConstants#AMOUNT_SCALE} 를 재사용한다(중복 선언 금지) —
 * V10 의 {@code general_payouts.amount NUMERIC(19,2)} 와 일치.
 */
public final class GeneralPayoutConstants {

    /** D-G3: 해약환급률표 — 경과월수 구간 하한(개월) → 환급률. 단일 선언. */
    public static final NavigableMap<Integer, BigDecimal> SURRENDER_REFUND_RATE_TIERS;

    static {
        NavigableMap<Integer, BigDecimal> tiers = new TreeMap<>();
        tiers.put(0, new BigDecimal("0.00"));
        tiers.put(12, new BigDecimal("0.40"));
        tiers.put(36, new BigDecimal("0.60"));
        tiers.put(60, new BigDecimal("0.75"));
        tiers.put(84, new BigDecimal("0.85"));
        SURRENDER_REFUND_RATE_TIERS = Collections.unmodifiableNavigableMap(tiers);
    }

    /**
     * D-G3: 만기보험금 환급률 — 기납입보험료의 100%.
     * V1 은 전 상품을 만기환급형으로 취급한다(상품별 환급형 플래그는 향후 과제).
     */
    public static final BigDecimal MATURITY_REFUND_RATE = BigDecimal.ONE;

    /** 연 보험료를 회차 보험료로 나눌 때의 연간 개월 수. */
    public static final int MONTHS_PER_YEAR = 12;

    /** 적용 요율 스냅샷 스케일 — V10 의 {@code applied_rate NUMERIC(5,4)} 와 일치. */
    public static final int RATE_SCALE = 4;

    private GeneralPayoutConstants() {
        // static utility
    }
}
