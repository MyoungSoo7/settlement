package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.GetDataRecordsUseCase;
import github.lms.lemuel.commondata.application.port.in.GetDataSourcesUseCase;
import github.lms.lemuel.commondata.application.port.out.LoadDataRecordPort;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.domain.DataRecord;
import github.lms.lemuel.commondata.domain.DataSource;
import github.lms.lemuel.commondata.domain.DataSourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 데이터소스·수집 레코드 공개 조회 서비스.
 *
 * <p>조회는 캐시({@code dataSources}/{@code dataRecords}) — 수집·등록이 upsert 후
 * 캐시를 evict 해 정합을 유지한다(TTL 만 믿지 않는다).
 */
@Service
@Transactional(readOnly = true)
public class DataQueryService implements GetDataSourcesUseCase, GetDataRecordsUseCase {

    /** 레코드 조회 상한 — 범용 저장소라 무제한 조회는 막는다. */
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 100;

    private final LoadDataSourcePort loadDataSourcePort;
    private final LoadDataRecordPort loadDataRecordPort;

    public DataQueryService(LoadDataSourcePort loadDataSourcePort,
                            LoadDataRecordPort loadDataRecordPort) {
        this.loadDataSourcePort = loadDataSourcePort;
        this.loadDataRecordPort = loadDataRecordPort;
    }

    @Override
    @Cacheable(cacheNames = "dataSources", key = "'ALL'")
    public List<DataSource> getSources() {
        return loadDataSourcePort.findAll();
    }

    @Override
    @Cacheable(cacheNames = "dataSources", key = "#code")
    public DataSource getSource(String code) {
        return loadDataSourcePort.findByCode(code)
                .orElseThrow(() -> new DataSourceNotFoundException(code));
    }

    @Override
    @Cacheable(cacheNames = "dataRecords", key = "#sourceCode + ':' + #limit")
    public List<DataRecord> getRecords(String sourceCode, int limit) {
        // 존재검증(404)이 기간·페이징 해석보다 먼저다.
        if (loadDataSourcePort.findByCode(sourceCode).isEmpty()) {
            throw new DataSourceNotFoundException(sourceCode);
        }
        int resolved = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return loadDataRecordPort.findLatest(sourceCode, resolved);
    }
}
