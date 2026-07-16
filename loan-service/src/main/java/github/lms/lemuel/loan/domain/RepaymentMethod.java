package github.lms.lemuel.loan.domain;

/**
 * 대출 상환방식(상환방법). 원금·기간·이자율이 같아도 방식에 따라 회차별 납입 구조가 달라진다.
 *
 * <ul>
 *   <li>{@link #BULLET} 만기일시상환 — 매기 <b>이자만</b> 납입하고 원금은 만기에 전액 일시 상환.
 *       총이자가 가장 크지만 만기 전까지 상환 부담이 가볍다.</li>
 *   <li>{@link #EQUAL_PAYMENT} 원리금균등상환 — 매기 <b>납입액(원금+이자)이 동일</b>.
 *       초반엔 이자 비중이 크고 갈수록 원금 비중이 커진다.</li>
 *   <li>{@link #EQUAL_PRINCIPAL} 원금균등상환 — 매기 <b>납입 원금이 동일</b>하고 잔액이 줄며 이자가 감소.
 *       초반 부담이 크지만 총이자가 가장 작다.</li>
 * </ul>
 *
 * <p>세 방식의 회차 스케줄 산정은 결정적 순수 함수({@link RepaymentSchedule})가 단일 출처로 담당한다.
 */
public enum RepaymentMethod {

    /** 만기일시상환 — 매기 이자만, 원금은 만기 일시. */
    BULLET("만기일시상환"),

    /** 원리금균등상환 — 매기 납입액 동일. */
    EQUAL_PAYMENT("원리금균등상환"),

    /** 원금균등상환 — 매기 납입 원금 동일. */
    EQUAL_PRINCIPAL("원금균등상환");

    private final String label;

    RepaymentMethod(String label) {
        this.label = label;
    }

    /** 사람이 읽는 한글 방식명. */
    public String label() {
        return label;
    }
}
