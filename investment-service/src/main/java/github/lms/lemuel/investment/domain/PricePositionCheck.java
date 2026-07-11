package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시세 위치 체크(R4·R5) 결과 — 추격매수 구간(3거래일 연속 상승)·52주 고점 부근(-5% 이내) 여부.
 *
 * <p>시세는 market-service 일별 종가 기준(전일까지 — 실시간 아님). CAUTION 은 종목 탈락이 아니라
 * "진입 시점 주의"(추격 구간이면 대기, 고점 부근이면 감점) 신호다.
 */
public record PricePositionCheck(Status status, LocalDate baseDate, BigDecimal latestClose,
                                 int riseStreakDays, boolean chaseRisk,
                                 BigDecimal fiftyTwoWeekHigh, BigDecimal fiftyTwoWeekLow,
                                 BigDecimal highGapPercent, boolean nearHigh) {

    public enum Status {
        /** 추격 구간 아님 + 고점 부근 아님. */
        OK,
        /** 추격매수 구간(연속 상승 3일 이상) 또는 52주 고점 -5% 이내. */
        CAUTION,
        /** market-service 에 시세 데이터 없음(종목 미등록 포함). */
        NO_DATA,
        /** 시세 원천 호출 실패 — 이 축만 강등. */
        UNAVAILABLE
    }

    /** 최신 종가 확보 여부 — 매매계획(TradePlan) 산정 가능 조건. */
    public boolean hasQuote() {
        return latestClose != null;
    }

    public static PricePositionCheck noData() {
        return new PricePositionCheck(Status.NO_DATA, null, null, 0, false, null, null, null, false);
    }

    public static PricePositionCheck unavailable() {
        return new PricePositionCheck(Status.UNAVAILABLE, null, null, 0, false, null, null, null, false);
    }
}
