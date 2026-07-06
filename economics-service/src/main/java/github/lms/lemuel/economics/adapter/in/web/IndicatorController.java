package github.lms.lemuel.economics.adapter.in.web;

import github.lms.lemuel.economics.application.port.in.GetIndicatorSeriesUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase.IndicatorSnapshot;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorValue;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 경제지표 공개 조회 API — 카탈로그+최신값/변동, 단건 최신값, 시계열.
 *
 * <p>전부 공개 거시 데이터라 무인증(GET). 응답 DTO 는 도메인 노출을 막는 컨트롤러 내부 record.
 */
@RestController
@RequestMapping("/api/economics/indicators")
public class IndicatorController {

    private final GetIndicatorsUseCase getIndicatorsUseCase;
    private final GetIndicatorSeriesUseCase getIndicatorSeriesUseCase;

    public IndicatorController(GetIndicatorsUseCase getIndicatorsUseCase,
                               GetIndicatorSeriesUseCase getIndicatorSeriesUseCase) {
        this.getIndicatorsUseCase = getIndicatorsUseCase;
        this.getIndicatorSeriesUseCase = getIndicatorSeriesUseCase;
    }

    @GetMapping
    public List<IndicatorSnapshotResponse> indicators() {
        return getIndicatorsUseCase.getIndicators().stream()
                .map(IndicatorSnapshotResponse::from)
                .toList();
    }

    @GetMapping("/{code}/latest")
    public IndicatorSnapshotResponse latest(@PathVariable String code) {
        return IndicatorSnapshotResponse.from(getIndicatorsUseCase.getIndicator(code));
    }

    @GetMapping("/{code}/series")
    public SeriesResponse series(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 존재검증 겸 메타데이터(name/unit) — 없는 code 는 여기서 404 (기간검증보다 우선).
        Indicator indicator = getIndicatorsUseCase.getIndicator(code).indicator();
        // 기간검증(from>to→400) + 시계열 조회. getSeries 도 자체적으로 존재검증하지만(캐시 오염 방지),
        // 여기서는 메타데이터가 필요해 getIndicator 를 먼저 부른다(이중 존재검증은 무해).
        List<IndicatorValue> points = getIndicatorSeriesUseCase.getSeries(code, from, to);
        return SeriesResponse.from(indicator, points);
    }

    // ----- 응답 DTO (컨트롤러 내부 record) -----

    record IndicatorSnapshotResponse(String code, String name, String unit, String cycle,
                                     LatestPoint latest, ChangeResponse change) {
        static IndicatorSnapshotResponse from(IndicatorSnapshot snapshot) {
            Indicator indicator = snapshot.indicator();
            LatestPoint latest = snapshot.latest() == null ? null
                    : new LatestPoint(snapshot.latest().observedDate(), snapshot.latest().value());
            ChangeResponse change = snapshot.change() == null ? null
                    : new ChangeResponse(snapshot.change().amount(), snapshot.change().ratePercent());
            return new IndicatorSnapshotResponse(
                    indicator.code(), indicator.name(), indicator.unit(),
                    indicator.cycle().name(), latest, change);
        }
    }

    record LatestPoint(LocalDate observedDate, BigDecimal value) { }

    record ChangeResponse(BigDecimal amount, BigDecimal ratePercent) { }

    record SeriesResponse(String code, String name, String unit, List<SeriesPoint> points) {
        static SeriesResponse from(Indicator indicator, List<IndicatorValue> values) {
            List<SeriesPoint> points = values.stream()
                    .map(v -> new SeriesPoint(v.observedDate(), v.value(), v.source().name()))
                    .toList();
            return new SeriesResponse(indicator.code(), indicator.name(), indicator.unit(), points);
        }
    }

    record SeriesPoint(LocalDate observedDate, BigDecimal value, String source) { }
}
