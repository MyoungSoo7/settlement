package github.lms.lemuel.economics.application.service;

import github.lms.lemuel.economics.application.port.in.GetIndicatorSeriesUseCase;
import github.lms.lemuel.economics.application.port.in.GetIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorPort;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorValuePort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorNotFoundException;
import github.lms.lemuel.economics.domain.IndicatorValue;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 지표 공개 조회 서비스.
 *
 * <p>카탈로그 스냅샷(최신값+전기 대비 변동)과 시계열을 제공한다. 변동은 저장하지 않고
 * {@link IndicatorValue#changeFrom} 으로 계산한다. 조회는 캐시({@code indicatorSnapshots}/
 * {@code indicatorSeries}) — 수집 배치가 upsert 후 캐시를 evict 해 정합을 유지한다
 * (TTL 만 믿지 않는다).
 */
@Service
@Transactional(readOnly = true)
public class IndicatorQueryService implements GetIndicatorsUseCase, GetIndicatorSeriesUseCase {

    private final LoadIndicatorPort loadIndicatorPort;
    private final LoadIndicatorValuePort loadIndicatorValuePort;

    public IndicatorQueryService(LoadIndicatorPort loadIndicatorPort,
                                 LoadIndicatorValuePort loadIndicatorValuePort) {
        this.loadIndicatorPort = loadIndicatorPort;
        this.loadIndicatorValuePort = loadIndicatorValuePort;
    }

    @Override
    @Cacheable("indicatorSnapshots")
    public List<IndicatorSnapshot> getIndicators() {
        // 지표 4개 규모라 지표별 findLatest(N+1)를 허용 — 카탈로그가 커지면 배치 조회로 전환.
        return loadIndicatorPort.findAll().stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    @Cacheable(cacheNames = "indicatorSnapshots", key = "#code")
    public IndicatorSnapshot getIndicator(String code) {
        Indicator indicator = loadIndicatorPort.findByCode(code)
                .orElseThrow(() -> new IndicatorNotFoundException(code));
        return toSnapshot(indicator);
    }

    /**
     * 지표 시계열 조회. 순서: 존재검증(404) → 기간검증(400) → 조회.
     *
     * <p>from/to 생략 시 캐시 키가 {@code "code:null:null"} 로 고정되므로, 그날 첫 호출이 잡은
     * {@code [now-1y, now]} 범위가 TTL(600s) 동안 재사용된다 — 즉 "현재일" 이 최대 TTL 만큼
     * drift 할 수 있다(실시간성보다 캐시 히트를 택한 의도적 트레이드오프).
     */
    @Override
    @Cacheable(cacheNames = "indicatorSeries", key = "#code + ':' + #from + ':' + #to")
    public List<IndicatorValue> getSeries(String code, LocalDate from, LocalDate to) {
        // 존재검증을 먼저 — 오타/unknown code 가 빈 결과로 indicatorSeries 캐시를 오염시키는 걸 막고,
        // unknown + from>to 동시 입력 시 404(400 아님)가 우선하도록 한다.
        if (loadIndicatorPort.findByCode(code).isEmpty()) {
            throw new IndicatorNotFoundException(code);
        }
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusYears(1);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException(
                    "조회 기간이 올바르지 않습니다: from=" + resolvedFrom + ", to=" + resolvedTo);
        }
        return loadIndicatorValuePort.findSeries(code, resolvedFrom, resolvedTo);
    }

    /** 최신 관측치 최대 2건으로 latest+전기 대비 변동을 조립 (관측치 부족 시 null). */
    private IndicatorSnapshot toSnapshot(Indicator indicator) {
        List<IndicatorValue> latest2 = loadIndicatorValuePort.findLatest(indicator.code(), 2);
        if (latest2.isEmpty()) {
            return new IndicatorSnapshot(indicator, null, null);
        }
        IndicatorValue latest = latest2.get(0);
        IndicatorValue.Change change = latest2.size() >= 2 ? latest.changeFrom(latest2.get(1)) : null;
        return new IndicatorSnapshot(indicator, latest, change);
    }
}
