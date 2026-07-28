package github.lms.lemuel.investment.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 일일 스크리닝 실행 판정 — <b>시세 기준일 앵커 + 미갱신 스킵</b>. 순수 도메인.
 *
 * <p>시세는 T+1 로 적재되므로(market 은 매일 07:30 에 <i>전일</i> 종가를 수집) 실행 시점에 "오늘이
 * 거래일인가"를 시세로 판정하는 것은 불가능하다 — 오늘 종가는 어차피 아직 없다. 대신 <b>새 종가가
 * 도착했는가</b>를 기준으로 삼으면 거래일 캘린더 없이 같은 목적을 달성한다:
 *
 * <ul>
 *   <li>휴장일에는 새 종가가 생기지 않으므로 다음 실행이 {@link ScreeningTrigger.Decision#SKIP_UP_TO_DATE}
 *       로 떨어진다 — 거래가 없던 날짜로 추천 세트가 생기지 않는다.</li>
 *   <li>추천일은 실행일이 아니라 <b>산출 근거가 된 종가일</b>이 된다 — 세트의 기준일이 곧 추천일이라
 *       "언제 종가로 뽑은 추천인가"가 화면에서 모호해지지 않는다.</li>
 * </ul>
 *
 * <p>시세를 한 건도 못 구한 경우(원천 장애·종목 미등록)는 판단 근거가 없으므로 스킵한다 — 근거 없이
 * 스크리닝하면 전 종목이 시세 없음으로 탈락해 빈 세트가 되고, 화면의 최신 세트가 근거 없이 흔들린다.
 */
public class ScreeningTriggerPolicy {

    /**
     * @param universeCloses 유니버스 전 종목의 종가 시계열을 합친 것(정렬·종목 구분 무관, null 허용)
     * @param latestSetDate  저장된 최신 추천 세트의 추천일(없으면 null)
     */
    public ScreeningTrigger decide(List<DailyClose> universeCloses, LocalDate latestSetDate) {
        Optional<LocalDate> quoteBaseDate = latestQuoteDate(universeCloses);
        if (quoteBaseDate.isEmpty()) {
            return ScreeningTrigger.skipNoQuotes();
        }
        LocalDate baseDate = quoteBaseDate.get();
        // 미래 날짜 세트(앵커 변경 이전의 '실행일' 기준 잔존 세트)도 '같지 않음'이므로 스크리닝한다 —
        // 한 번 돌고 나면 세트가 시세 기준일로 정렬돼 스스로 수렴한다.
        if (baseDate.equals(latestSetDate)) {
            return ScreeningTrigger.skipUpToDate(baseDate);
        }
        return ScreeningTrigger.screen(baseDate);
    }

    /** 유니버스 전체에서 가장 최신 종가일 — 한 종목이 거래정지·지연이어도 나머지가 기준일을 정한다. */
    private static Optional<LocalDate> latestQuoteDate(List<DailyClose> closes) {
        if (closes == null) {
            return Optional.empty();
        }
        return closes.stream().map(DailyClose::date).max(Comparator.naturalOrder());
    }
}
