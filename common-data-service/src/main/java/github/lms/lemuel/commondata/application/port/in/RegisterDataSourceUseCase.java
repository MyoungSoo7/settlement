package github.lms.lemuel.commondata.application.port.in;

import github.lms.lemuel.commondata.domain.DataSource;

import java.util.List;
import java.util.Map;

public interface RegisterDataSourceUseCase {

    /**
     * code 기준 upsert — 이미 있으면 null 이 아닌 필드만 덮어쓰는 부분 갱신.
     * 유효성(코드 패턴·endpoint 형식)은 {@link DataSource} 도메인 생성자가 강제한다.
     */
    DataSource register(RegisterCommand command);

    record RegisterCommand(String code, String name, String endpoint,
                           Map<String, String> defaultParams, List<String> keyFields,
                           Integer pageSize, Boolean enabled, String description) { }
}
