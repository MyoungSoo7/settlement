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

    /** @param provider DATA_GO_KR(기본)/SEOUL_OPENAPI — null 이면 기존 값 보존(신규는 기본값) */
    record RegisterCommand(String code, String name, String endpoint, String provider,
                           Map<String, String> defaultParams, List<String> keyFields,
                           Integer pageSize, Boolean enabled, String description) { }
}
