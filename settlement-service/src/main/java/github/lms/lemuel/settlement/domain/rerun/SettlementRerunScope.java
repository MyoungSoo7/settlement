package github.lms.lemuel.settlement.domain.rerun;

import java.util.List;

/**
 * 운영자가 재실행할 수 있는 정산 배치 단계.
 *
 * <p>각 단계는 두 축으로 분류된다:
 * <ul>
 *   <li>{@link #movesMoney()} — 실자금(펌뱅킹 송금)이 나가는가. true 인 단계는 {@link #ALL} 전개에서
 *       제외되어 <b>운영자가 명시적으로 지정해야만</b> 실행된다. 재계산 단계는 몇 번을 돌려도 결과가
 *       같지만, 송금은 "한 번 더 눌렀다"가 곧 사고이므로 뭉뚱그린 ALL 에 섞지 않는다.</li>
 *   <li>{@link #dateScoped()} — targetDate 로 대상이 좁혀지는가. PAYOUT_EXECUTE 는 REQUESTED 상태
 *       전량이 대상이라 날짜와 무관하다(날짜를 받아도 무시된다는 사실을 타입으로 드러낸다).</li>
 * </ul>
 */
public enum SettlementRerunScope {

    /** 만기 정산 확정 배치 — REQUESTED 만 읽어 확정하므로 재실행 안전. */
    CONFIRM(false, true),

    /** 홀드백 해제 — 해제일 도래분만 대상이라 재실행해도 중복 해제되지 않는다. */
    HOLDBACK_RELEASE(false, true),

    /** 지급 실행(펌뱅킹) — 실자금 이동. ALL 에 포함되지 않는다. */
    PAYOUT_EXECUTE(true, false),

    /** 재계산 경로 일괄 — CONFIRM → HOLDBACK_RELEASE 순. 자금 이동 단계는 포함하지 않는다. */
    ALL(false, true);

    private final boolean movesMoney;
    private final boolean dateScoped;

    SettlementRerunScope(boolean movesMoney, boolean dateScoped) {
        this.movesMoney = movesMoney;
        this.dateScoped = dateScoped;
    }

    /** 실자금이 이동하는 단계인가 — true 면 ALL 전개 제외 + 감사 로그에 별도 표기. */
    public boolean movesMoney() {
        return movesMoney;
    }

    /** targetDate 로 대상이 좁혀지는 단계인가. */
    public boolean dateScoped() {
        return dateScoped;
    }

    /**
     * 실제 실행할 단계 목록으로 전개한다.
     *
     * <p>{@link #ALL} 은 <b>자금을 움직이지 않는 단계만</b> 선언 순서대로 전개한다 — 여기에
     * PAYOUT_EXECUTE 를 넣는 순간 "일괄 재실행"이 송금 버튼이 되어버린다.
     */
    public List<SettlementRerunScope> expand() {
        if (this != ALL) {
            return List.of(this);
        }
        return List.of(CONFIRM, HOLDBACK_RELEASE);
    }
}
