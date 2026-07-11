package github.lms.lemuel.investment.domain;

import java.util.List;

/**
 * 거시 컨텍스트(기준금리·국고채3년·환율·CPI) — "하락이 시장 전체 요인인가 종목 고유 요인인가"를
 * 분리해 보는 참고 지표 묶음. 판정 없이 최신값만 담는다.
 */
public record MacroCheck(Status status, List<EconomicIndicatorSnapshot> indicators) {

    public enum Status {
        /** 지표 스냅샷 확보. */
        OK,
        /** economics-service 응답에 지표 없음. */
        NO_DATA,
        /** 거시 원천 호출 실패 — 이 축만 강등. */
        UNAVAILABLE
    }

    public static MacroCheck of(List<EconomicIndicatorSnapshot> indicators) {
        return indicators.isEmpty()
                ? new MacroCheck(Status.NO_DATA, List.of())
                : new MacroCheck(Status.OK, List.copyOf(indicators));
    }

    public static MacroCheck unavailable() {
        return new MacroCheck(Status.UNAVAILABLE, List.of());
    }
}
