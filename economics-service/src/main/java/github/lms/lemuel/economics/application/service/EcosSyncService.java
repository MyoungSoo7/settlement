package github.lms.lemuel.economics.application.service;

import github.lms.lemuel.economics.application.port.in.SyncIndicatorsUseCase;
import github.lms.lemuel.economics.application.port.in.SyncResult;
import github.lms.lemuel.economics.application.port.out.EcosClientPort;
import github.lms.lemuel.economics.application.port.out.LoadIndicatorPort;
import github.lms.lemuel.economics.application.port.out.SaveIndicatorValuePort;
import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * ECOS 수집 배치.
 *
 * <p>대상 지표(전체 또는 특정 code)의 [from, to] 관측치를 ECOS 에서 받아
 * {@code (indicator_code, observed_date)} UNIQUE upsert 로 저장(SEED → ECOS 대체)한다.
 *
 * <p>호출 간 간격(requestIntervalMs)으로 ECOS 쿼터를 보호하고, 개별 지표 실패는 집계만 하고
 * 계속 진행한다(전체 배치가 한 지표 때문에 죽지 않게).
 */
@Service
public class EcosSyncService implements SyncIndicatorsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EcosSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 10;

    private final EcosClientPort ecosClient;
    private final LoadIndicatorPort loadIndicatorPort;
    private final SaveIndicatorValuePort saveIndicatorValuePort;
    private final long requestIntervalMs;

    public EcosSyncService(EcosClientPort ecosClient,
                           LoadIndicatorPort loadIndicatorPort,
                           SaveIndicatorValuePort saveIndicatorValuePort,
                           @Value("${app.economics.sync.request-interval-ms:150}") long requestIntervalMs) {
        this.ecosClient = ecosClient;
        this.loadIndicatorPort = loadIndicatorPort;
        this.saveIndicatorValuePort = saveIndicatorValuePort;
        this.requestIntervalMs = requestIntervalMs;
    }

    @Override
    // 관측치 upsert 후 조회 캐시를 비워 정합 유지 — TTL(600s) 만 믿지 않는다.
    // allEntries=true 는 특정 지표 하나만 sync 해도 전체를 evict 하는 coarse 한 무효화지만,
    // 카탈로그 N 이 작아(현재 4개) 재적재 비용이 무시할 만해 지표별 세분 evict 대신 채택.
    @CacheEvict(cacheNames = {"indicatorSnapshots", "indicatorSeries"}, allEntries = true)
    public SyncResult syncIndicators(String indicatorCode, LocalDate from, LocalDate to) {
        requireConfigured();
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("조회 기간이 올바르지 않습니다: from=" + from + ", to=" + to);
        }
        List<Indicator> targets = resolveTargets(indicatorCode);

        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int failed = 0;
        for (Indicator indicator : targets) {
            scanned++;
            try {
                List<EcosClientPort.Observation> observations =
                        ecosClient.fetchObservations(indicator, from, to);
                if (observations.isEmpty()) {
                    skipped++;   // ECOS 응답 0건 (결측 구간 등)
                } else {
                    LocalDate latestEcos = observations.get(0).observedDate();
                    for (EcosClientPort.Observation obs : observations) {
                        saveIndicatorValuePort.upsert(new IndicatorValue(
                                null, indicator.code(), obs.observedDate(), obs.value(),
                                ValueSource.ECOS, null));
                        upserted++;
                        if (obs.observedDate().isAfter(latestEcos)) {
                            latestEcos = obs.observedDate();
                        }
                    }
                    // SEED 는 오늘까지 가짜 미래치를 채워둔다 — 실 ECOS 최신일 이후의 후행 SEED 를 잘라
                    // 헤드라인 최신값이 시드가 아니라 실데이터를 가리키게 한다.
                    int purged = saveIndicatorValuePort.purgeSeedNewerThan(indicator.code(), latestEcos);
                    if (purged > 0) {
                        log.info("후행 SEED {}건 제거 code={} (실 ECOS 최신일 {} 이후)",
                                purged, indicator.code(), latestEcos);
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("지표 동기화 실패 code={}: {}", indicator.code(), e.getMessage(), e);
            }
            logProgress(scanned, targets.size(), upserted, failed);
            pause();
        }
        log.info("ECOS 동기화 완료 — 스캔 {}, upsert {}, 스킵 {}, 실패 {}", scanned, upserted, skipped, failed);
        return new SyncResult(scanned, upserted, skipped, failed);
    }

    private List<Indicator> resolveTargets(String indicatorCode) {
        if (indicatorCode == null) {
            return loadIndicatorPort.findAll();
        }
        return List.of(loadIndicatorPort.findByCode(indicatorCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "존재하지 않는 지표 코드입니다: " + indicatorCode)));
    }

    private void requireConfigured() {
        if (!ecosClient.isConfigured()) {
            throw new IllegalStateException("ECOS API 키가 설정되지 않았습니다 (ECOS_API_KEY)");
        }
    }

    private void logProgress(int scanned, int total, int upserted, int failed) {
        if (scanned % PROGRESS_LOG_INTERVAL == 0) {
            log.info("ECOS 동기화 진행 {}/{} (upsert {}, 실패 {})", scanned, total, upserted, failed);
        }
    }

    private void pause() {
        if (requestIntervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(requestIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동기화 스레드 인터럽트", e);
        }
    }
}
