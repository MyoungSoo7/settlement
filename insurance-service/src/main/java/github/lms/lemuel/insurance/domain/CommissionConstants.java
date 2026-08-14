package github.lms.lemuel.insurance.domain;

/**
 * 수수료 도메인 상수.
 *
 * <p><b>D6 — 환수 창구</b>: {@link #CLAWBACK_WINDOW_MONTHS} 선언 한 줄이 이 값의 유일한 정의 지점이다.
 * 다른 어떤 비-테스트 코드도 같은 숫자를 리터럴로 다시 적지 않는다 (매직넘버 금지).
 */
public final class CommissionConstants {

    /**
     * D6: 환수(clawback) 창구 (개월).
     *
     * <p>계약 효력일(effective_date)부터 종료일까지 완료된 개월 수 m 과 이 창구 W 를 놓고:
     * <ul>
     *   <li>m >= W 이면 환수액 0</li>
     *   <li>m < W 이면 환수액 = 기지급 합계 × (W - m) / W, 통화 최소단위 절사(RoundingPolicy 준수)</li>
     *   <li>청약철회(CANCELLED)는 m 무관 전액 환수</li>
     *   <li>환수 트리거 상태: SURRENDERED, CANCELLED, LAPSED→EXPIRED(부활기간 도과)</li>
     * </ul>
     *
     * <p>D7 의 부활 가능 창구({@code Policy.REINSTATEMENT_WINDOW_MONTHS})도 같은 값을 이 상수에서 참조한다.
     */
    public static final int CLAWBACK_WINDOW_MONTHS = 24;

    /** D4: 선지급 회차 수 — 초년도 총액은 정확히 이 개수의 행으로 분할된다. */
    public static final int INSTALLMENT_COUNT = 12;

    /**
     * 금액 스케일 — 통화 최소단위 절사 기준.
     * V1 마이그레이션의 commission_schedule.installment_amount NUMERIC(19,2) 와 일치한다.
     */
    public static final int AMOUNT_SCALE = 2;

    /** D5: FC 채널의 수수료 수령 주체. 계층(오버라이딩) 계산은 미구현. */
    public static final String RECIPIENT_TYPE_FC = "FC";

    /** V6: 방카(BANCA) 채널의 수수료 수령 주체 — 판매 은행. */
    public static final String RECIPIENT_TYPE_BANK = "BANK";

    private CommissionConstants() {
        // static utility
    }
}
