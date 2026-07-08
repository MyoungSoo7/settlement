package github.lms.lemuel.commondata.application.service;

import github.lms.lemuel.commondata.application.port.in.RegisterDataSourceUseCase;
import github.lms.lemuel.commondata.application.port.out.LoadDataSourcePort;
import github.lms.lemuel.commondata.application.port.out.SaveDataSourcePort;
import github.lms.lemuel.commondata.domain.DataSource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 데이터소스 등록/수정 (운영자 전용 경로에서만 호출).
 *
 * <p>code 기준 upsert — 기존 소스가 있으면 요청에서 null 인 필드는 기존 값을 보존하는
 * 부분 갱신이다. 유효성은 {@link DataSource} 도메인 생성자가 강제한다(신규인데 name/endpoint
 * 누락이면 여기서 IllegalArgumentException → 400).
 */
@Service
public class DataSourceAdminService implements RegisterDataSourceUseCase {

    private final LoadDataSourcePort loadDataSourcePort;
    private final SaveDataSourcePort saveDataSourcePort;

    public DataSourceAdminService(LoadDataSourcePort loadDataSourcePort,
                                  SaveDataSourcePort saveDataSourcePort) {
        this.loadDataSourcePort = loadDataSourcePort;
        this.saveDataSourcePort = saveDataSourcePort;
    }

    @Override
    @CacheEvict(cacheNames = {"dataSources", "dataRecords"}, allEntries = true)
    public DataSource register(RegisterCommand command) {
        if (command == null || command.code() == null) {
            throw new IllegalArgumentException("code 은(는) 필수입니다");
        }
        DataSource existing = loadDataSourcePort.findByCode(command.code()).orElse(null);
        DataSource merged = new DataSource(
                existing == null ? null : existing.id(),
                command.code(),
                command.name() != null ? command.name()
                        : existing != null ? existing.name() : null,
                command.endpoint() != null ? command.endpoint()
                        : existing != null ? existing.endpoint() : null,
                command.defaultParams() != null ? command.defaultParams()
                        : existing != null ? existing.defaultParams() : Map.of(),
                command.keyFields() != null ? command.keyFields()
                        : existing != null ? existing.keyFields() : List.of(),
                command.pageSize() != null ? command.pageSize()
                        : existing != null ? existing.pageSize() : DataSource.DEFAULT_PAGE_SIZE,
                command.enabled() != null ? command.enabled()
                        : existing == null || existing.enabled(),
                command.description() != null ? command.description()
                        : existing != null ? existing.description() : null,
                null);
        return saveDataSourcePort.upsert(merged);
    }
}
