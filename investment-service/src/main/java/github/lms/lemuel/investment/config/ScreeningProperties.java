package github.lms.lemuel.investment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 일일 종목 스크리닝 설정({@code app.screening.*}).
 *
 * <p>유니버스(스크리닝 후보 목록)는 외부 서비스가 "전체 종목 리스트" API 를 제공하지 않아 설정으로 주입한다.
 * {@code sector} 는 InvestmentScore 에 없으므로 여기서 함께 지정한다(추천 세트의 업종 표기·분산 기준).
 */
@ConfigurationProperties(prefix = "app.screening")
public record ScreeningProperties(
        List<UniverseEntry> universe,
        int maxPicks,
        boolean sectorDiversify,
        String cron,
        String zone) {

    public ScreeningProperties {
        universe = universe == null ? List.of() : List.copyOf(universe);
        if (maxPicks <= 0) {
            maxPicks = 3;
        }
        zone = (zone == null || zone.isBlank()) ? "Asia/Seoul" : zone;
    }

    /** 유니버스 1종목 — 종목코드 + 업종(추천 세트 표기·분산용). */
    public record UniverseEntry(String code, String sector) {
    }
}
