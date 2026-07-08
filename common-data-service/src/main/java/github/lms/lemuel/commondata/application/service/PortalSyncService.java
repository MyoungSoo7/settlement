package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.SyncDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.in.SyncResult;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort;
import github.lms.lemuel.commondata.application.port.out.DataPortalClientPort.PortalItem;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataRecordPort;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 공공데이터포털 수집 배치.
 *
 * <p>등록된 데이터소스 1개의 전 페이지를 받아 {@code (source, recordKey)} UNIQUE upsert 로
 * 저장한다(재수집 멱등). 개별 레코드 저장 실패는 집계만 하고 계속 진행한다 — 한 아이템의
 * 이상값으로 수집 전체를 죽이지 않는다.
 */
@Service
public class PortalSyncService implements SyncDataSourceUseCase {

    private static final Logger log = LoggerFactory.getLogger(PortalSyncService.class);

    private final DataPortalClientPort portalClient;
    private final LoadDataSourcePort loadDataSourcePort;
    private final SaveDataRecordPort saveDataRecordPort;

    public PortalSyncService(DataPortalClientPort portalClient,
                             LoadDataSourcePort loadDataSourcePort,
                             SaveDataRecordPort saveDataRecordPort) {
        this.portalClient = portalClient;
        this.loadDataSourcePort = loadDataSourcePort;
        this.saveDataRecordPort = saveDataRecordPort;
    }

    @Override
    // 레코드 upsert 후 조회 캐시를 비워 정합 유지 — TTL(600s) 만 믿지 않는다.
    @CacheEvict(cacheNames = {"dataSources", "dataRecords"}, allEntries = true)
    public SyncResult sync(String sourceCode, Map<String, String> overrideParams) {
        if (!portalClient.isConfigured()) {
            throw new IllegalStateException(
                    "공공데이터포털 인증키가 설정되지 않았습니다 (DATA_GO_KR_API_KEY)");
        }
        DataSource source = loadDataSourcePort.findByCode(sourceCode)
                .orElseThrow(() -> new DataSourceNotFoundException(sourceCode));
        if (!source.enabled()) {
            throw new IllegalStateException("비활성 데이터소스입니다: " + sourceCode);
        }

        var items = portalClient.fetchItems(source,
                overrideParams == null ? Map.of() : overrideParams);

        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int failed = 0;
        Instant collectedAt = Instant.now();
        for (PortalItem item : items) {
            scanned++;
            if (item == null || item.recordKey() == null || item.recordKey().isBlank()
                    || item.payloadJson() == null || item.payloadJson().isBlank()) {
                skipped++;   // 키/본문 결측 — 저장 스킵
                continue;
            }
            try {
                saveDataRecordPort.upsert(new DataRecord(
                        null, sourceCode, item.recordKey(), item.payloadJson(), collectedAt));
                upserted++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("레코드 저장 실패 source={} key={}: {}",
                        sourceCode, item.recordKey(), e.getMessage());
            }
        }
        log.info("공공데이터 수집 완료 source={} — 스캔 {}, upsert {}, 스킵 {}, 실패 {}",
                sourceCode, scanned, upserted, skipped, failed);
        return new SyncResult(scanned, upserted, skipped, failed);
    }
}
